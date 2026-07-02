/**
 * Document DTO shapes mirroring the OpenAPI Console contract.
 *
 * These live under features/documents/model so the mapper can import them
 * without crossing into services/http (the architecture test forbids any
 * `features/**` file from importing `services/http`). The transport returns
 * the raw `result` payload as `unknown`; the mapper narrows against these
 * types.
 *
 * Source of truth: docs/api/console-api.openapi.yaml —
 *   DocumentResponse, DocumentListResponse, and the retry/upload endpoints.
 *
 * IDs are decimal strings in the wire contract; they MUST stay as `string`
 * through the whole pipeline — never numericised.
 */

/** Document file type as reported by the server. */
export type FileType = 'TXT' | 'MARKDOWN';

/** Ingestion lifecycle states. */
export type IngestionStatus = 'PENDING' | 'PROCESSING' | 'READY' | 'FAILED';

/**
 * GET .../documents/{docId} | POST .../documents (202) | POST .../retry (200)
 * result payload.
 */
export interface DocumentResponseDto {
  /** Decimal-string id; never a JS number. */
  readonly docId: string;
  readonly knowledgeBaseId: string;
  /** Original user-provided filename. */
  readonly originalFilename: string;
  readonly fileType: FileType;
  /** File size in bytes; non-negative. */
  readonly sizeBytes: number;
  readonly ingestionStatus: IngestionStatus;
  /** Stringified version counter for CAS on retry. */
  readonly operationVersion: string;
  /** 1-based attempt counter. */
  readonly attempt: number;
  /** Empty string when no failure; mapper coerces to null. */
  readonly failureCategory: string;
  /** Server-safe failure text; empty string when none. */
  readonly failureMessage: string;
  /** True when retry is allowed (FAILED + retryable category + under cap). */
  readonly retryable: boolean;
  /** ISO-8601 or null when not yet started. */
  readonly startedAt: string | null;
  /** ISO-8601 or null when not yet completed. */
  readonly completedAt: string | null;
}

/** GET .../documents result payload (paged). */
export interface DocumentListResponseDto {
  readonly items: ReadonlyArray<DocumentResponseDto>;
  /** Empty string signals "no more pages". */
  readonly nextPageToken: string;
}
