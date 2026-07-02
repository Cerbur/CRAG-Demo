/**
 * Console API client with single-flight refresh + max-one replay.
 *
 * Flow per request:
 *   1. Read Access Token from {@link SessionStore}; inject `Authorization: Bearer`.
 *   2. Call {@link executeRequest}.
 *   3. On success → return `result`.
 *   4. On auth-marked 401:
 *      a. If this is already a replay (flagged on the request), rethrow.
 *      b. Otherwise join the single-flight refresh promise (creating it if absent).
 *      c. If refresh succeeds → write the new token to SessionStore, mark the
 *         request as replayed, re-execute once. Result or rethrown error returned.
 *      d. If refresh fails → clear SessionStore and reject every queued request
 *         with the refresh ApiError. Subsequent requests will see an absent
 *         token and skip straight to a 401 path (which will re-trigger clear).
 *
 * Single-flight invariants (api-client.md §2):
 *  - Exactly one refresh is in flight for the whole page at any time.
 *  - Every 401-failed request replays at most once after refresh resolves.
 *  - Refresh failure clears the session and rejects queued requests.
 *
 * The refresh request itself:
 *   POST /console-api/api/v1/auth/refresh
 *   credentials: 'include'  (refresh_token HttpOnly cookie)
 *   Body: empty (server reads cookie only).
 *
 * The transport adds credentials:'include' for every request already; the
 * refresh is no different. We do NOT manually send Origin/Referer — the
 * browser adds them automatically for same-origin requests via the runtime
 * proxy.
 */
import { ApiErrorException } from './api-error';
import { defaultSessionStore, type SessionStore } from './session-store';
import {
  executeRequest,
  isAuthMarkedError,
  replayRequest,
  type FetchLike,
  type TransportLogEntry,
  type TransportOptions,
} from './transport';
import type { HttpRequest } from './types';

/** Refresh response DTO shape (mirrors AuthResponse from OpenAPI). */
interface RefreshResult {
  readonly accessToken: string;
  readonly accessExpiresAt: string;
  readonly user: { readonly userId: string; readonly nickname: string };
  readonly defaultTenant: unknown;
}

/** Per-request marker: this request has already replayed once. */
interface ReplayAware extends HttpRequest {
  readonly __replayed?: true;
}

export interface ConsoleClientOptions {
  readonly fetch?: FetchLike | undefined;
  readonly sessionStore: SessionStore;
  readonly log?: ((entry: TransportLogEntry) => void) | undefined;
  /**
   * Path prefixes whose 401 responses must NOT trigger the single-flight
   * refresh loop. Defaults to the auth endpoints themselves
   * (`/console-api/api/v1/auth/register|login|refresh|logout`): a 401 on those
   * is a real auth outcome the caller must surface, not a recoverable expired
   * Access JWT. The bootstrap's `/auth/me` 401 still triggers refresh because
   * it is not in this list.
   */
  readonly skipRefreshPaths?: ReadonlyArray<RegExp> | undefined;
}

/** HTTP client contract consumed by feature ViewModels. */
export interface HttpClient {
  request<T>(request: HttpRequest): Promise<T>;
}

/** Build the standard transport options for a client. */
function transportOptions(opts: ConsoleClientOptions): TransportOptions {
  if (opts.fetch) {
    return { fetch: opts.fetch, log: opts.log };
  }
  return { log: opts.log };
}

/** Refresh path; relative per web/constraints/api-client.md §1. */
const REFRESH_PATH = '/console-api/api/v1/auth/refresh';

/**
 * Default skip-refresh list: auth endpoints themselves. A 401 on register or
 * login is a real credential failure (the user is actively authenticating, so
 * there is no live session to recover); a 401 on refresh/logout has no
 * meaningful "refresh the refresh" semantics. /auth/me is intentionally NOT
 * here — its 401 during bootstrap is exactly the signal that triggers the
 * single-flight refresh loop.
 */
const DEFAULT_SKIP_REFRESH: ReadonlyArray<RegExp> = [
  /^\/console-api\/api\/v1\/auth\/register(?:\?|$)/,
  /^\/console-api\/api\/v1\/auth\/login(?:\?|$)/,
  /^\/console-api\/api\/v1\/auth\/refresh(?:\?|$)/,
  /^\/console-api\/api\/v1\/auth\/logout(?:\?|$)/,
];

/**
 * Create a Console client. The {@link consoleClient} singleton below uses the
 * default SessionStore; tests construct their own with a fresh store.
 */
export function createConsoleClient(opts: ConsoleClientOptions): HttpClient & {
  /** Test hook: expose the in-flight refresh promise (undefined when idle). */
  readonly __refreshInFlight?: Promise<RefreshResult>;
} {
  const transport = transportOptions(opts);
  const skipRefresh: ReadonlyArray<RegExp> = opts.skipRefreshPaths ?? DEFAULT_SKIP_REFRESH;
  let refreshInFlight: Promise<RefreshResult> | null = null;

  const shouldSkipRefresh = (path: string): boolean =>
    skipRefresh.some((re) => re.test(path));

  const doRefresh = async (): Promise<RefreshResult> => {
    const refreshReq: HttpRequest = {
      method: 'POST',
      path: REFRESH_PATH,
    };
    const { result } = await executeRequest(refreshReq, transport);
    return result as RefreshResult;
  };

  const singleFlightRefresh = (): Promise<RefreshResult> => {
    if (refreshInFlight) return refreshInFlight;
    const p = doRefresh().finally(() => {
      // Clear the slot so a later 401 starts a new refresh.
      // Use setTimeout(0) to ensure all queued .then() handlers resolve first.
      // (Microtask timing: finally runs before the queued then; queueMicrotask
      //  would still see refreshInFlight set. setTimeout guarantees order.)
      setTimeout(() => {
        refreshInFlight = null;
      }, 0);
    });
    refreshInFlight = p;
    return p;
  };

  const handleClearSession = (): void => {
    opts.sessionStore.clear();
  };

  const request = async <T>(req: HttpRequest): Promise<T> => {
    const token = opts.sessionStore.getAccessToken();
    const headers: Record<string, string> = { ...req.headers };
    if (token) {
      headers['Authorization'] = `Bearer ${token}`;
    }
    const withAuth: HttpRequest = { ...req, headers };

    try {
      const { result } = await executeRequest(withAuth, transport);
      return result as T;
    } catch (err) {
      if (!isAuthMarkedError((err as ApiErrorException).apiError)) {
        throw err;
      }
      // Auth endpoints (register/login/refresh/logout): surface the 401 as-is.
      // There is no live session to recover; a refresh attempt would either
      // loop or mask the real credential failure.
      if (shouldSkipRefresh(req.path)) {
        throw err;
      }
      // Already-replayed request: do not loop.
      if ((req as ReplayAware).__replayed) {
        throw err;
      }

      let refreshResult: RefreshResult;
      try {
        refreshResult = await singleFlightRefresh();
      } catch (refreshErr) {
        // Refresh failed — clear session and reject every queued request.
        handleClearSession();
        throw refreshErr instanceof ApiErrorException
          ? refreshErr
          : new ApiErrorException({
              kind: 'authentication',
              message: 'Session expired',
              retryable: false,
              fieldErrors: [],
            });
      }
      // Refresh succeeded — store new token and replay once.
      opts.sessionStore.setAccessToken(refreshResult.accessToken);
      const replayReq: ReplayAware = { ...req, headers: { ...req.headers }, __replayed: true };
      // Re-attach the fresh token.
      const replayHeaders: Record<string, string> = { ...replayReq.headers };
      replayHeaders['Authorization'] = `Bearer ${refreshResult.accessToken}`;
      return replayRequest<T>({ ...replayReq, headers: replayHeaders }, transport);
    }
  };

  return {
    request,
    get __refreshInFlight() {
      return refreshInFlight ?? undefined;
    },
  } as HttpClient & { readonly __refreshInFlight?: Promise<RefreshResult> };
}

/**
 * Production Console client singleton. Uses the default in-memory SessionStore.
 * Feature code imports this directly; ViewModels call `consoleClient.request`.
 */
export const consoleClient: HttpClient = createConsoleClient({
  sessionStore: defaultSessionStore,
});
