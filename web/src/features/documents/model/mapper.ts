/**
 * Pure mappers from Document DTOs (raw `result` payloads returned by the
 * transport) to the {@link DocumentItem} / {@link DocumentPage} domain types.
 *
 * The transport returns `result` as `unknown`. Each mapper validates the shape
 * defensively and throws a typed {@link DocumentDtoError} when the contract is
 * violated — never trusting unchecked casts.
 *
 * Domain invariants:
 *  - `id` is ALWAYS a string (renamed from wire `docId`). Never numericised —
 *    large ids would silently lose precision as JS numbers.
 *  - `filename` is renamed from wire `originalFilename`.
 *  - `status` is renamed from wire `ingestionStatus`.
 *  - Empty-string `failureMessage`/`failureCategory` coerce to null/'' so the
 *    View never shows an empty "failure" line.
 *  - `updatedAt` prefers `completedAt`, falls back to `startedAt`, else null.
 */
import type {
  DocumentListResponseDto,
  DocumentResponseDto,
  FileType,
  IngestionStatus,
} from './dto';

/** Allowed ingestion lifecycle statuses (re-exported for the View). */
export type { IngestionStatus, FileType } from './dto';

/** Domain Document item, flattened for UI consumption. */
export interface DocumentItem {
  /** Decimal-string id (renamed from wire `docId`). */
  readonly id: string;
  readonly knowledgeBaseId: string;
  /** Renamed from wire `originalFilename`. */
  readonly filename: string;
  readonly sizeBytes: number;
  /** Renamed from wire `ingestionStatus`. */
  readonly status: IngestionStatus;
  readonly attempt: number;
  readonly retryable: boolean;
  /** Server-safe failure text, or null when no failure. */
  readonly failureMessage: string | null;
  /** completedAt || startedAt || null. */
  readonly updatedAt: string | null;
}

/** Paged list of Documents. Empty nextPageToken === end of list. */
export interface DocumentPage {
  readonly items: ReadonlyArray<DocumentItem>;
  readonly nextPageToken: string;
}

/** Thrown when a DTO does not match the OpenAPI contract. */
export class DocumentDtoError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'DocumentDtoError';
  }
}

const ALLOWED_STATUSES: ReadonlySet<IngestionStatus> = new Set([
  'PENDING',
  'PROCESSING',
  'READY',
  'FAILED',
]);

const ALLOWED_FILE_TYPES: ReadonlySet<FileType> = new Set(['TXT', 'MARKDOWN']);

function isObject(v: unknown): v is Record<string, unknown> {
  return typeof v === 'object' && v !== null;
}

function requireString(obj: Record<string, unknown>, key: string): string {
  const v = obj[key];
  if (typeof v !== 'string') {
    throw new DocumentDtoError(`expected string field "${key}"`);
  }
  return v;
}

function requireNonEmptyString(obj: Record<string, unknown>, key: string): string {
  const v = obj[key];
  if (typeof v !== 'string' || v.length === 0) {
    throw new DocumentDtoError(`expected non-empty string field "${key}"`);
  }
  return v;
}

function requireInt(obj: Record<string, unknown>, key: string, min: number): number {
  const v = obj[key];
  if (typeof v !== 'number' || !Number.isFinite(v) || v < min || Math.floor(v) !== v) {
    throw new DocumentDtoError(`expected integer field "${key}" >= ${min}`);
  }
  return v;
}

function requireBoolean(obj: Record<string, unknown>, key: string): boolean {
  const v = obj[key];
  if (typeof v !== 'boolean') {
    throw new DocumentDtoError(`expected boolean field "${key}"`);
  }
  return v;
}

function optionalStringOrNull(obj: Record<string, unknown>, key: string): string | null {
  const v = obj[key];
  return typeof v === 'string' ? v : null;
}

/** Narrow an unknown payload to a validated DocumentResponseDto. */
function asDocumentDto(v: unknown): DocumentResponseDto {
  if (!isObject(v)) throw new DocumentDtoError('document response is not an object');
  const ingestionStatus = requireString(v, 'ingestionStatus') as IngestionStatus;
  if (!ALLOWED_STATUSES.has(ingestionStatus)) {
    throw new DocumentDtoError(`unknown ingestionStatus "${ingestionStatus}"`);
  }
  const fileType = requireString(v, 'fileType') as FileType;
  if (!ALLOWED_FILE_TYPES.has(fileType)) {
    throw new DocumentDtoError(`unknown fileType "${fileType}"`);
  }
  return {
    docId: requireNonEmptyString(v, 'docId'),
    knowledgeBaseId: requireNonEmptyString(v, 'knowledgeBaseId'),
    originalFilename: requireString(v, 'originalFilename'),
    fileType,
    sizeBytes: requireInt(v, 'sizeBytes', 0),
    ingestionStatus,
    operationVersion: requireString(v, 'operationVersion'),
    attempt: requireInt(v, 'attempt', 0),
    failureCategory: typeof v['failureCategory'] === 'string' ? (v['failureCategory'] as string) : '',
    failureMessage: typeof v['failureMessage'] === 'string' ? (v['failureMessage'] as string) : '',
    retryable: requireBoolean(v, 'retryable'),
    startedAt: optionalStringOrNull(v, 'startedAt'),
    completedAt: optionalStringOrNull(v, 'completedAt'),
  };
}

function asDocumentListDto(v: unknown): DocumentListResponseDto {
  if (!isObject(v)) throw new DocumentDtoError('document list is not an object');
  const itemsRaw = v['items'];
  if (!Array.isArray(itemsRaw)) throw new DocumentDtoError('list.items is not an array');
  const items = itemsRaw.map(asDocumentDto);
  const nextPageTokenRaw = v['nextPageToken'];
  if (typeof nextPageTokenRaw !== 'string') {
    throw new DocumentDtoError('list.nextPageToken must be a string');
  }
  return { items, nextPageToken: nextPageTokenRaw };
}

/** Map a single DTO (GET/{id}, POST upload 202, POST retry 200) to domain Model. */
export function mapDocumentDto(result: unknown): DocumentItem {
  const dto = asDocumentDto(result);
  const updatedAt = dto.completedAt ?? dto.startedAt ?? null;
  const failureMessage = dto.failureMessage.length > 0 ? dto.failureMessage : null;
  return {
    id: dto.docId,
    knowledgeBaseId: dto.knowledgeBaseId,
    filename: dto.originalFilename,
    sizeBytes: dto.sizeBytes,
    status: dto.ingestionStatus,
    attempt: dto.attempt,
    retryable: dto.retryable,
    failureMessage,
    updatedAt,
  };
}

/** Map a list DTO (GET list) to the domain page Model. */
export function mapDocumentListDto(result: unknown): DocumentPage {
  const dto = asDocumentListDto(result);
  return {
    items: dto.items.map((d) => ({
      id: d.docId,
      knowledgeBaseId: d.knowledgeBaseId,
      filename: d.originalFilename,
      sizeBytes: d.sizeBytes,
      status: d.ingestionStatus,
      attempt: d.attempt,
      retryable: d.retryable,
      failureMessage: d.failureMessage.length > 0 ? d.failureMessage : null,
      updatedAt: d.completedAt ?? d.startedAt ?? null,
    })),
    nextPageToken: dto.nextPageToken,
  };
}
