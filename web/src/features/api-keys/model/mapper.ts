/**
 * Pure mappers from API Key DTOs (raw `result` payloads returned by the
 * transport) to the {@link ApiKeyItem} / {@link CreatedApiKey} domain types.
 *
 * The transport returns `result` as `unknown`. Each mapper validates the shape
 * defensively and throws a typed {@link ApiKeyDtoError} when the contract is
 * violated — never trusting unchecked casts.
 *
 * Domain invariants:
 *  - `id` is ALWAYS a string (renamed from wire `apiKeyId`). Never numericised.
 *  - `status` from the wire may include EXPIRED; we surface the raw value via
 *    {@link ApiKeyItem.statusForDisplay} for the tag, but the canonical
 *    {@link ApiKeyItem.status} collapses EXPIRED to REVOKED for action-matrix
 *    purposes (both have no allowed actions).
 *  - `completeKey` from CreatedApiKeyResponse is preserved on {@link CreatedApiKey}
 *    so the View can show it ONCE in the modal; it must NEVER be placed into
 *    the TanStack Query cache. The mapper itself is pure and does not cache.
 *  - `keyPrefix` for a CreatedApiKey is derived from the completeKey's visible
 *    prefix (the public prefix part), so the list can render the row before the
 *    next list refetch arrives.
 */
import type {
  ApiKeyListResponseDto,
  ApiKeyResponseDto,
  ApiKeyStatusDto,
  CreateApiKeyRequestDto,
  CreatedApiKeyResponseDto,
} from './dto';

/** User-facing lifecycle status. EXPIRED is collapsed to REVOKED here. */
export type ApiKeyStatus = 'ACTIVE' | 'DISABLED' | 'REVOKED';

/** Display status — preserves EXPIRED for the tag colour. */
export type ApiKeyDisplayStatus = ApiKeyStatus | 'EXPIRED';

/** Domain API Key item (list/detail row). Mirrors ApiKeyResponse with id alias. */
export interface ApiKeyItem {
  /** Decimal-string id (renamed from wire `apiKeyId`). */
  readonly id: string;
  readonly knowledgeBaseId: string;
  readonly name: string;
  /** Searchable prefix; NEVER the complete secret. */
  readonly keyPrefix: string;
  /** Canonical status for the action matrix (EXPIRED → REVOKED). */
  readonly status: ApiKeyStatus;
  /** Raw status for display (may be EXPIRED). */
  readonly statusForDisplay: ApiKeyDisplayStatus;
  /** ISO-8601 UTC or null (no expiry). */
  readonly expiresAt: string | null;
}

/** Result of create/rotate; carries the one-time completeKey. */
export interface CreatedApiKey extends ApiKeyItem {
  /** Complete key — show ONCE, then purge. Never cached in TanStack Query. */
  readonly completeKey: string;
}

/** Paged list of API Keys. Empty/null nextPageToken === end of list. */
export interface ApiKeyPage {
  readonly items: ReadonlyArray<ApiKeyItem>;
  readonly nextPageToken: string;
}

/** Thrown when a DTO does not match the OpenAPI contract. */
export class ApiKeyDtoError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'ApiKeyDtoError';
  }
}

/** Collapse a wire status to the canonical action-matrix status. */
function canonicalStatus(s: ApiKeyStatusDto): ApiKeyStatus {
  if (s === 'EXPIRED') return 'REVOKED';
  return s;
}

function isObject(v: unknown): v is Record<string, unknown> {
  return typeof v === 'object' && v !== null;
}

function requireString(obj: Record<string, unknown>, key: string): string {
  const v = obj[key];
  if (typeof v !== 'string' || v.length === 0) {
    throw new ApiKeyDtoError(`expected non-empty string field "${key}"`);
  }
  return v;
}

function requireStatus(obj: Record<string, unknown>, key: string): ApiKeyStatusDto {
  const v = obj[key];
  if (v !== 'ACTIVE' && v !== 'DISABLED' && v !== 'REVOKED' && v !== 'EXPIRED') {
    throw new ApiKeyDtoError(`expected ApiKeyStatus field "${key}", got "${String(v)}"`);
  }
  return v;
}

function optionalStringOrNull(obj: Record<string, unknown>, key: string): string | null {
  const v = obj[key];
  return typeof v === 'string' ? v : null;
}

/** Derive the public keyPrefix from a completeKey (e.g. "crag_abcd…" → "crag_abcd"). */
export function deriveKeyPrefix(completeKey: string): string {
  // The wire format is `crag_<prefix>_<secret>`. We expose everything up to the
  // last underscore-delimited segment's first character as the prefix — i.e. the
  // same shape the server returns in ApiKeyResponse.keyPrefix. For safety we
  // never include more than the first two dash/underscore-delimited tokens.
  // If the shape is unexpected, fall back to the first 12 characters.
  const match = /^([a-zA-Z0-9_]+?_[a-zA-Z0-9]{4})/.exec(completeKey);
  if (match) return match[1]!;
  return completeKey.slice(0, 12);
}

/** Narrow an unknown payload to a validated ApiKeyResponseDto. */
function asApiKeyDto(v: unknown): ApiKeyResponseDto {
  if (!isObject(v)) throw new ApiKeyDtoError('api key response is not an object');
  return {
    apiKeyId: requireString(v, 'apiKeyId'),
    knowledgeBaseId: requireString(v, 'knowledgeBaseId'),
    name: requireString(v, 'name'),
    status: requireStatus(v, 'status'),
    keyPrefix: requireString(v, 'keyPrefix'),
    createdAt: requireString(v, 'createdAt'),
    expiresAt: optionalStringOrNull(v, 'expiresAt'),
  };
}

/** Narrow an unknown payload to a validated ApiKeyListResponseDto. */
function asApiKeyListDto(v: unknown): ApiKeyListResponseDto {
  if (!isObject(v)) throw new ApiKeyDtoError('api key list is not an object');
  const itemsRaw = v['items'];
  if (!Array.isArray(itemsRaw)) throw new ApiKeyDtoError('list.items is not an array');
  const items = itemsRaw.map(asApiKeyDto);
  const nextPageTokenRaw = v['nextPageToken'];
  // Tolerate both null and string (the contract allows null for keys).
  if (nextPageTokenRaw !== null && typeof nextPageTokenRaw !== 'string') {
    throw new ApiKeyDtoError('list.nextPageToken must be a string or null');
  }
  return { items, nextPageToken: nextPageTokenRaw };
}

function toDomain(dto: ApiKeyResponseDto): ApiKeyItem {
  return {
    id: dto.apiKeyId,
    knowledgeBaseId: dto.knowledgeBaseId,
    name: dto.name,
    keyPrefix: dto.keyPrefix,
    status: canonicalStatus(dto.status),
    statusForDisplay: dto.status,
    expiresAt: dto.expiresAt,
  };
}

/** Map a single ApiKeyResponse DTO (GET/{id}, disable/enable/revoke 200) to domain. */
export function mapApiKeyDto(result: unknown): ApiKeyItem {
  return toDomain(asApiKeyDto(result));
}

/** Map an ApiKeyListResponse DTO (GET list) to the domain page Model. */
export function mapApiKeyListDto(result: unknown): ApiKeyPage {
  const dto = asApiKeyListDto(result);
  return {
    items: dto.items.map(toDomain),
    nextPageToken: dto.nextPageToken ?? '',
  };
}

/**
 * Map a CreatedApiKeyResponse DTO (POST create 201, POST rotate 200) to a
 * {@link CreatedApiKey}. Derives `keyPrefix` from the completeKey and sets
 * `status` to ACTIVE (a freshly created/rotated key is always ACTIVE).
 *
 * IMPORTANT: the returned object carries `completeKey`. The caller (ViewModel)
 * is responsible for ensuring this value is shown once and then purged — it
 * must NEVER be placed into the TanStack Query cache.
 */
export function mapCreatedApiKeyDto(result: unknown): CreatedApiKey {
  if (!isObject(result)) throw new ApiKeyDtoError('created api key response is not an object');
  const dto: CreatedApiKeyResponseDto = {
    apiKeyId: requireString(result, 'apiKeyId'),
    knowledgeBaseId: requireString(result, 'knowledgeBaseId'),
    name: requireString(result, 'name'),
    completeKey: requireString(result, 'completeKey'),
    expiresAt: optionalStringOrNull(result, 'expiresAt'),
  };
  return {
    id: dto.apiKeyId,
    knowledgeBaseId: dto.knowledgeBaseId,
    name: dto.name,
    keyPrefix: deriveKeyPrefix(dto.completeKey),
    status: 'ACTIVE',
    statusForDisplay: 'ACTIVE',
    expiresAt: dto.expiresAt,
    completeKey: dto.completeKey,
  };
}

/** Validate a create request body. Returns it unchanged on success. */
export function toCreateApiKeyRequest(
  name: string,
  ttlSeconds?: number,
): CreateApiKeyRequestDto {
  const trimmed = name.trim();
  if (trimmed.length < 1 || trimmed.length > 64) {
    throw new ApiKeyDtoError('name must be 1..64 characters');
  }
  if (ttlSeconds !== undefined) {
    if (
      !Number.isFinite(ttlSeconds) ||
      Math.floor(ttlSeconds) !== ttlSeconds ||
      ttlSeconds < 0 ||
      ttlSeconds > 31_536_000
    ) {
      throw new ApiKeyDtoError('ttlSeconds must be an integer in 0..31536000');
    }
  }
  return ttlSeconds === undefined ? { name: trimmed } : { name: trimmed, ttlSeconds };
}
