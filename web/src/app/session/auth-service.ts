/**
 * Auth orchestration service.
 *
 * Lives under app/session (NOT features/auth) because it must import the
 * Console client + SessionStore, and the architecture test forbids any
 * `features/**` file from importing `services/http`. ViewModels in pages call
 * these commands and translate thrown {@link ApiErrorException} into form
 * errors via the shared `mapApiError`/`ApiError` pipeline.
 *
 * Tenant recovery rule (docs/api/README.md §2):
 *  - register returns defaultTenant non-null → use directly.
 *  - login/refresh return defaultTenant=null → fetch GET /api/v1/tenants and
 *    pick the first tenant to populate the working tenant context.
 *
 * Access Token is written to the in-memory SessionStore on success; refresh
 * token is HttpOnly cookie, never visible to JS.
 */
import type { AuthSession } from '@entities/session';
import type { HttpClient } from '@services/http/console-client';
import type { SessionStore } from '@services/http/session-store';
import {
  buildSessionFromLogin,
  buildSessionFromMe,
  buildSessionFromRegister,
} from '@features/auth/model/mapper';
import type {
  LoginRequestBody,
  RegisterRequestBody,
} from '@features/auth/model/schema';

/** Injectable dependencies. Tests pass a fresh SessionStore + isolated client. */
export interface AuthServiceDeps {
  readonly client: HttpClient;
  readonly sessionStore: SessionStore;
}

/** Wire paths. Relative per web/constraints/api-client.md §1. */
const REGISTER_PATH = '/console-api/api/v1/auth/register';
const LOGIN_PATH = '/console-api/api/v1/auth/login';
const ME_PATH = '/console-api/api/v1/auth/me';
const TENANTS_PATH = '/console-api/api/v1/tenants';
const LOGOUT_PATH = '/console-api/api/v1/auth/logout';

/**
 * Register a new user. The response carries a non-null defaultTenant which is
 * used to seed the session. On success the access JWT is written to the
 * SessionStore; failures propagate as {@link ApiErrorException}.
 */
export async function registerNewUser(
  deps: AuthServiceDeps,
  body: RegisterRequestBody,
): Promise<{ readonly session: AuthSession }> {
  const result = await deps.client.request<unknown>({
    method: 'POST',
    path: REGISTER_PATH,
    body,
  });
  const built = buildSessionFromRegister(result);
  deps.sessionStore.setAccessToken(built.accessToken);
  return { session: built.session };
}

/**
 * Login with username/password. The login response carries defaultTenant=null,
 * so we additionally fetch GET /api/v1/tenants and pick the first tenant to
 * populate the working context. On success the access JWT is written to the
 * SessionStore.
 */
export async function loginWithCredentials(
  deps: AuthServiceDeps,
  body: LoginRequestBody,
): Promise<{ readonly session: AuthSession }> {
  const authResult = await deps.client.request<unknown>({
    method: 'POST',
    path: LOGIN_PATH,
    body,
  });
  const tenantsResult = await deps.client.request<unknown>({
    method: 'GET',
    path: TENANTS_PATH,
  });
  const built = buildSessionFromLogin(authResult, tenantsResult);
  deps.sessionStore.setAccessToken(built.accessToken);
  return { session: built.session };
}

/**
 * Best-effort logout. Calls POST /api/v1/auth/logout (the server clears the
 * refresh cookie regardless of Origin outcome) and clears the local session.
 * Never throws — failures are swallowed because the local session is cleared
 * unconditionally.
 */
export async function logout(deps: AuthServiceDeps): Promise<void> {
  try {
    await deps.client.request<unknown>({ method: 'POST', path: LOGOUT_PATH });
  } catch {
    // Best-effort: the server clears the cookie in its finally block; the
    // local session is cleared below regardless.
  }
  deps.sessionStore.clear();
}

/**
 * Attempt to restore a session from the HttpOnly refresh cookie. Used by the
 * bootstrap on page load when no Access Token is in memory.
 *
 * Flow:
 *   GET /auth/me → on 401 the Console client auto-refreshes via the cookie;
 *     if refresh succeeds, /me is replayed and we get the user projection.
 *   GET /tenants → recover the working tenant context.
 *
 * Returns null when the user is anonymous (no cookie / refresh failed / cross-
 * site origin / empty tenant list). Never throws — bootstrap failures degrade
 * to anonymous.
 */
export async function restoreFromCookie(deps: AuthServiceDeps): Promise<AuthSession | null> {
  try {
    const me = await deps.client.request<unknown>({ method: 'GET', path: ME_PATH });
    const tenants = await deps.client.request<unknown>({
      method: 'GET',
      path: TENANTS_PATH,
    });
    return buildSessionFromMe(me, tenants);
  } catch {
    return null;
  }
}
