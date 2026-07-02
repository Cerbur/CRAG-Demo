/**
 * Wire-level types shared by the Console/Open API clients.
 *
 * These mirror the OpenAPI 3.1 contracts (docs/api/console-api.openapi.yaml and
 * docs/api/open-api.openapi.yaml). The transport unwraps the envelope and only
 * hands `result` to feature code; failure envelopes are turned into `ApiError`
 * by {@link ./api-error.ts}.
 *
 * Design rules (see web/constraints/api-client.md):
 *  - The transport must not be aware of any business DTO; it only knows the
 *    envelope shape and the stable ErrorDetail contract.
 *  - `result` is intentionally `unknown` so each feature mapper validates and
 *    narrows it. The transport never assumes a particular schema.
 */

/** Query string parameters; values are string/number/boolean/null. */
export type QueryValue = string | number | boolean | null;
export type QueryParams = Readonly<Record<string, QueryValue | ReadonlyArray<QueryValue>>>;

/** HTTP method set used by the Console/Open APIs. */
export type HttpMethod = 'GET' | 'POST' | 'PATCH' | 'PUT' | 'DELETE';

/**
 * Outgoing HTTP request. `body` is JSON-serialisable; multipart uploads will
 * extend this in task 22.5 — for now JSON is the only supported body shape.
 *
 * The `headers` map is intentionally partial; the transport injects the
 * Authorization header from `SessionStore`/per-request API Key and never
 * trusts caller-supplied auth headers.
 */
export interface HttpRequest {
  readonly method: HttpMethod;
  /** Path beginning with `/console-api` or `/open-api` (relative prefix). */
  readonly path: string;
  readonly query?: QueryParams;
  readonly headers?: Readonly<Record<string, string>>;
  readonly body?: unknown;
  /** Multipart/form-data body (raw FormData). Mutually exclusive with `body`. */
  readonly form?: FormData;
  /**
   * Per-request Bearer API Key. Only used by the Open client. When set, the
   * transport adds `Authorization: Bearer <key>` and skips SessionStore.
   */
  readonly bearerApiKey?: string;
}

/**
 * The unified response envelope (OpenAPI EnvelopeBase + result).
 *
 * `result` is `unknown` on success and the transport returns it as-is. On
 * failure `result` is `ErrorDetailDto | null`.
 */
export interface Envelope {
  readonly success: boolean;
  readonly code: number;
  readonly result: unknown;
}

/** Field-level validation error. `rejectedValue` is NOT trusted (may be null). */
export interface FieldErrorDto {
  readonly field: string;
  readonly message: string;
  readonly rejectedValue?: unknown;
}

/** Stable error detail returned inside an error envelope. */
export interface ErrorDetailDto {
  readonly message: string;
  readonly traceId: string;
  readonly reason: string;
  readonly retryable: boolean;
  readonly fieldErrors: ReadonlyArray<FieldErrorDto>;
}

/** Token used to replay a request after a single-flight refresh succeeds. */
export type RequestIdempotencyToken = string;
