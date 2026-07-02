/**
 * Pure error mapping from wire envelopes / HTTP failures to the stable
 * {@link ApiError} domain type.
 *
 * Classification rules (web/constraints/api-client.md §4 + plan_22/22.2):
 *
 * | HTTP | code   | reason              | kind            |
 * |------|--------|---------------------|-----------------|
 * | 400  | 40001  | VALIDATION_ERROR    | validation      |
 * | 400  | 40002  | INVALID_ARGUMENT    | validation      |
 * | 401  | 40101  | UNAUTHENTICATED     | authentication  |
 * | 401  | 40102  | INVALID_CREDENTIALS | authentication  |
 * | 403  | 40301  | FORBIDDEN           | authorization   |
 * | 404  | 40401  | NOT_FOUND           | business        |
 * | 409  | 40901  | CONFLICT            | business        |
 * | 409  | 40902  | INGESTION_RETRY...  | business        |
 * | 413  | 41301  | UPLOAD_TOO_LARGE    | business        |
 * | 415  | 41501  | UNSUPPORTED_MEDIA.. | business        |
 * | 500  | 50001  | INTERNAL_ERROR      | unknown         |
 * | 502  | 50201  | LLM_UNAVAILABLE     | retryable       |
 * | 503  | 50301  | DOWNSTREAM_UNAVAIL. | retryable       |
 * | 504  | 50401  | DOWNSTREAM_TIMEOUT  | retryable       |
 * | —    | —      | —                   | unknown         |
 *
 * The classifier never trusts the server's `message` for the `kind` decision;
 * it uses HTTP status + business code + stable reason. The `message` field
 * surfaced to callers is the server-provided safe stable text only.
 *
 * Security (api-client.md §4):
 *  - We never include `rejectedValue` in `fieldErrors`; only `field` + `message`.
 *  - Network errors produce a retryable `unknown` ApiError with a generic
 *    message; no host/url is leaked beyond the path the caller already knows.
 */
import type { ErrorDetailDto } from './types';

/** Stable domain error type consumed by ViewModels. */
export interface ApiError {
  readonly kind:
    'validation' | 'authentication' | 'authorization' | 'business' | 'retryable' | 'unknown';
  readonly message: string;
  readonly traceId?: string | undefined;
  readonly retryable: boolean;
  readonly fieldErrors: ReadonlyArray<{ readonly field: string; readonly message: string }>;
}

/** Sentinel codes from docs/api/README.md §9.1. */
const CODE = {
  VALIDATION_ERROR: 40001,
  INVALID_ARGUMENT: 40002,
  UNAUTHENTICATED: 40101,
  INVALID_CREDENTIALS: 40102,
  FORBIDDEN: 40301,
  NOT_FOUND: 40401,
  CONFLICT: 40901,
  INGESTION_RETRY_NOT_ALLOWED: 40902,
  UPLOAD_TOO_LARGE: 41301,
  UNSUPPORTED_MEDIA_TYPE: 41501,
  INTERNAL_ERROR: 50001,
  LLM_UNAVAILABLE: 50201,
  DOWNSTREAM_UNAVAILABLE: 50301,
  DOWNSTREAM_TIMEOUT: 50401,
} as const;

/**
 * Classify an {@link ApiError}.kind from HTTP status + business code. Pure;
 * does not touch the message.
 */
export function classifyErrorKind(status: number, code: number | undefined): ApiError['kind'] {
  // Business code is authoritative when present.
  if (typeof code === 'number') {
    if (code === CODE.VALIDATION_ERROR || code === CODE.INVALID_ARGUMENT) return 'validation';
    if (code === CODE.UNAUTHENTICATED || code === CODE.INVALID_CREDENTIALS) return 'authentication';
    if (code === CODE.FORBIDDEN) return 'authorization';
    if (
      code === CODE.NOT_FOUND ||
      code === CODE.CONFLICT ||
      code === CODE.INGESTION_RETRY_NOT_ALLOWED ||
      code === CODE.UPLOAD_TOO_LARGE ||
      code === CODE.UNSUPPORTED_MEDIA_TYPE
    ) {
      return 'business';
    }
    if (
      code === CODE.LLM_UNAVAILABLE ||
      code === CODE.DOWNSTREAM_UNAVAILABLE ||
      code === CODE.DOWNSTREAM_TIMEOUT
    ) {
      return 'retryable';
    }
    if (code === CODE.INTERNAL_ERROR) return 'unknown';
  }
  // Fall back to HTTP status.
  if (status === 400) return 'validation';
  if (status === 401) return 'authentication';
  if (status === 403) return 'authorization';
  if (status === 404 || status === 409 || status === 413 || status === 415) return 'business';
  if (status === 502 || status === 503 || status === 504) return 'retryable';
  return 'unknown';
}

/**
 * Map a structured envelope-level error (success=false) to {@link ApiError}.
 *
 * Classification prefers the stable `reason` label, then falls back to HTTP
 * status. The business `code` field is intentionally not passed in here —
 * callers below have already used it to gate the envelope-error path; `reason`
 * is the wire-level truth for the kind decision.
 *
 * `rejectedValue` from `fieldErrors` is dropped — feature code never needs it
 * and it must never reach logs/UI snapshots (api-client.md §4).
 */
export function mapApiError(status: number, detail: ErrorDetailDto): ApiError {
  const fallbackKind = classifyErrorKind(status, undefined);
  const finalKind = refineByReason(detail.reason, fallbackKind);
  return {
    kind: finalKind,
    message: detail.message,
    traceId: detail.traceId,
    retryable: Boolean(detail.retryable),
    fieldErrors: detail.fieldErrors.map((fe) => ({
      field: fe.field,
      message: fe.message,
    })),
  };
}

/**
 * Build an ApiError from a non-envelope situation: network failure, malformed
 * JSON, or a missing/empty body. The result is always retryable + unknown so
 * callers can surface a generic safe message and the ViewModel can choose to
 * retry.
 */
export function mapTransportError(
  reason: 'network' | 'parse' | 'empty',
  opts: { readonly path: string; readonly method: string; readonly traceId?: string } = {
    path: '',
    method: 'GET',
  },
): ApiError {
  const messages: Record<typeof reason, string> = {
    network: 'Network error',
    parse: 'Malformed server response',
    empty: 'Empty server response',
  } as const;
  return {
    kind: reason === 'network' ? 'retryable' : 'unknown',
    message: messages[reason],
    retryable: reason === 'network',
    traceId: opts.traceId,
    fieldErrors: [],
  };
}

/** Refine a kind based on the stable reason label (server-side truth). */
function refineByReason(reason: string, fallback: ApiError['kind']): ApiError['kind'] {
  const r = reason.toUpperCase();
  if (r === 'VALIDATION_ERROR' || r === 'INVALID_ARGUMENT' || r === 'INVALID_QUERY')
    return 'validation';
  if (
    r === 'UNAUTHENTICATED' ||
    r === 'INVALID_CREDENTIALS' ||
    r === 'MISSING_API_KEY' ||
    r === 'INVALID_API_KEY'
  ) {
    return 'authentication';
  }
  if (r === 'FORBIDDEN' || r === 'CROSS_SITE_ORIGIN') return 'authorization';
  if (
    r === 'NOT_FOUND' ||
    r === 'CONFLICT' ||
    r === 'INGESTION_RETRY_NOT_ALLOWED' ||
    r === 'UPLOAD_TOO_LARGE' ||
    r === 'UNSUPPORTED_EXTENSION' ||
    r === 'UNSUPPORTED_MEDIA_TYPE'
  ) {
    return 'business';
  }
  if (r === 'LLM_UNAVAILABLE' || r === 'DOWNSTREAM_UNAVAILABLE' || r === 'DOWNSTREAM_TIMEOUT') {
    return 'retryable';
  }
  if (r === 'INTERNAL_ERROR') return 'unknown';
  return fallback;
}

/** Custom Error subclass carrying {@link ApiError}; ViewModels rethrow as-is. */
export class ApiErrorException extends Error {
  readonly apiError: ApiError;
  constructor(apiError: ApiError) {
    super(apiError.message);
    this.name = 'ApiErrorException';
    this.apiError = apiError;
  }
}
