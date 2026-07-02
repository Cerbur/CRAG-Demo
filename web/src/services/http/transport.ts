/**
 * Injectable fetch wrapper that performs the actual HTTP call and unwraps the
 * unified Response<T> envelope.
 *
 * Responsibilities (web/constraints/api-client.md):
 *  1. Build the request: method, path, query serialization, headers, JSON body.
 *  2. Execute via the injected `fetch` (real `window.fetch` in production,
 *     MSW-attached fetch in tests). Transport never imports `window` directly.
 *  3. Translate network/parse failures into retryable `ApiError`.
 *  4. On 2xx: parse JSON, unwrap envelope; success envelope (`success && code===0`)
 *     → return `result`; failure envelope → throw {@link ApiErrorException}.
 *  5. On 401: surface an `ApiError` with kind `authentication` and a sentinel
 *     `retriable` flag set to `true` ONLY via the dedicated `isAuthError` mark
 *     so the Console client can single-flight refresh. (Open client ignores it.)
 *
 * Logging: only `method`, `path`, HTTP `status`, and `traceId` are recorded.
 * No Authorization headers, no body, no response payload (api-client.md §4).
 *
 * The transport is deliberately agnostic of auth: it does NOT read SessionStore
 * and does NOT inject the Authorization header. Clients (console/open) do that.
 */
import { ApiErrorException, mapApiError, mapTransportError, type ApiError } from './api-error';
import type { Envelope, ErrorDetailDto, HttpMethod, HttpRequest, QueryParams } from './types';

/** Fetch type compatible with the browser fetch and MSW. */
export type FetchLike = typeof globalThis.fetch;

/** Internal marker: transport tags auth errors so the Console client can react. */
export interface AuthMarkedApiError extends ApiError {
  readonly status: 401;
  readonly isAuthError: true;
}

/** A response carrying either the unwrapped `result` or a thrown ApiErrorException. */
export interface TransportResult {
  readonly status: number;
  readonly result: unknown;
}

/**
 * Build a URL with query string. Encodes keys; arrays use repeated keys.
 * Path is used verbatim (already prefixed with /console-api or /open-api).
 */
export function buildUrl(path: string, query?: QueryParams): string {
  if (!query) return path;
  const pairs: string[] = [];
  for (const [key, raw] of Object.entries(query)) {
    const values = Array.isArray(raw) ? raw : [raw];
    for (const v of values) {
      if (v === null || v === undefined) continue;
      pairs.push(`${encodeURIComponent(key)}=${encodeURIComponent(String(v))}`);
    }
  }
  if (pairs.length === 0) return path;
  const joiner = path.includes('?') ? (path.endsWith('&') ? '' : '&') : '?';
  return `${path}${joiner}${pairs.join('&')}`;
}

/** True if an ApiError carries the 401 single-flight marker. */
export function isAuthMarkedError(err: unknown): err is AuthMarkedApiError {
  return (
    typeof err === 'object' &&
    err !== null &&
    (err as { isAuthError?: unknown }).isAuthError === true &&
    (err as { status?: unknown }).status === 401
  );
}

/** Convert the wire envelope body to an ApiErrorException (or throw on malformed). */
function envelopeToError(status: number, body: unknown): ApiErrorException {
  if (typeof body !== 'object' || body === null) {
    throw new ApiErrorException(mapTransportError('parse', { path: '', method: 'GET' }));
  }
  const env = body as Partial<Envelope>;
  const detailRaw = env.result;
  if (typeof detailRaw !== 'object' || detailRaw === null) {
    // Defensive: server sent an envelope but result is not a structured ErrorDetail.
    throw new ApiErrorException({
      kind: status === 401 ? 'authentication' : 'unknown',
      message: 'Malformed server response',
      retryable: false,
      fieldErrors: [],
    });
  }
  const detail = detailRaw as Partial<ErrorDetailDto>;
  const safeDetail: ErrorDetailDto = {
    message: typeof detail.message === 'string' ? detail.message : 'Server error',
    traceId: typeof detail.traceId === 'string' ? detail.traceId : '',
    reason: typeof detail.reason === 'string' ? detail.reason : '',
    retryable: Boolean(detail.retryable),
    fieldErrors: Array.isArray(detail.fieldErrors)
      ? detail.fieldErrors.map((fe) => ({
          field: typeof fe?.field === 'string' ? fe.field : '',
          message: typeof fe?.message === 'string' ? fe.message : '',
          rejectedValue: fe?.rejectedValue,
        }))
      : [],
  };
  return new ApiErrorException(mapApiError(status, safeDetail));
}

export interface TransportOptions {
  /** Injectable fetch; defaults to globalThis.fetch. */
  readonly fetch?: FetchLike | undefined;
  /** Optional structured logger; receives {method, path, status, traceId} only. */
  readonly log?: ((entry: TransportLogEntry) => void) | undefined;
}

export interface TransportLogEntry {
  readonly method: HttpMethod;
  readonly path: string;
  readonly status: number;
  readonly traceId?: string | undefined;
}

/**
 * Perform an HTTP request and return the unwrapped `result`. Throws
 * {@link ApiErrorException} for any failure (network, parse, envelope-error,
 * non-2xx). For HTTP 401 the thrown error is auth-marked (see
 * {@link isAuthMarkedError}).
 */
export async function executeRequest(
  request: HttpRequest,
  options: TransportOptions = {},
): Promise<TransportResult> {
  const fetchImpl: FetchLike = options.fetch ?? globalThis.fetch.bind(globalThis);
  const headers = new Headers(request.headers);
  if (request.body !== undefined && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }

  // Body of the outgoing request: JSON string for object bodies, raw FormData
  // for multipart uploads, or absent entirely.
  const serializedBody: string | undefined =
    request.body !== undefined ? JSON.stringify(request.body) : undefined;
  // Build the fetch init. We construct it as a RequestInit by deriving the
  // type from the fetch signature itself (avoids naming the DOM-only lib
  // globals RequestInit/BodyInit which eslint's no-undef does not resolve).
  const init: Parameters<FetchLike>[1] = {
    method: request.method,
    headers,
    credentials: 'include',
  };
  if (serializedBody !== undefined) {
    init.body = serializedBody;
  } else if (request.form !== undefined) {
    init.body = request.form;
  }

  let response: Response;
  try {
    response = await fetchImpl(buildUrl(request.path, request.query), init);
  } catch {
    throw new ApiErrorException(
      mapTransportError('network', { path: request.path, method: request.method }),
    );
  }

  const status = response.status;
  const traceId: string | undefined = response.headers.get('X-Request-Id') ?? undefined;
  options.log?.({ method: request.method, path: request.path, status, traceId });

  // 204 / empty body — treat as null result envelope.
  const text = await response.text().catch(() => '');
  if (text === '') {
    if (status >= 200 && status < 300) {
      return { status, result: null };
    }
    throw new ApiErrorException(
      mapTransportError('empty', { path: request.path, method: request.method }),
    );
  }

  let body: unknown;
  try {
    body = JSON.parse(text);
  } catch {
    options.log?.({ method: request.method, path: request.path, status, traceId });
    throw new ApiErrorException(
      mapTransportError('parse', { path: request.path, method: request.method }),
    );
  }

  if (status === 401) {
    // Auth-marked error so the Console client can single-flight refresh.
    const base = envelopeToError(status, body);
    const marked: AuthMarkedApiError = { ...base.apiError, status: 401, isAuthError: true };
    throw new ApiErrorException(marked);
  }

  if (status < 200 || status >= 300) {
    throw envelopeToError(status, body);
  }

  const env = body as Partial<Envelope>;
  if (typeof env !== 'object' || env === null) {
    throw new ApiErrorException(
      mapTransportError('parse', { path: request.path, method: request.method }),
    );
  }
  if (env.success !== true || env.code !== 0) {
    // Server returned 2xx but envelope says failure — treat as envelope error.
    throw envelopeToError(status, body);
  }
  return { status, result: env.result };
}

/**
 * Re-execute a request after refresh. Used by the Console client's single-flight
 * replay. Exposed for testability.
 */
export async function replayRequest<T>(
  request: HttpRequest,
  options: TransportOptions,
): Promise<T> {
  const { result } = await executeRequest(request, options);
  return result as T;
}
