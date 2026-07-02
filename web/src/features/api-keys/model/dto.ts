/**
 * API Key DTO shapes mirroring the OpenAPI Console contract.
 *
 * These live under features/api-keys/model so the mapper can import them
 * without crossing into services/http (the architecture test forbids any
 * `features/**` file from importing `services/http`). The transport returns
 * the raw `result` payload as `unknown`; the mapper narrows against these
 * types.
 *
 * Source of truth: docs/api/console-api.openapi.yaml —
 *   ApiKeyResponse, ApiKeyListResponse, CreatedApiKeyResponse,
 *   CreateApiKeyRequest.
 *
 * IDs are decimal strings in the wire contract; they MUST stay as `string`
 * through the whole pipeline — never numericised.
 *
 * NOTE on `nextPageToken`: ApiKeyListResponse declares it as
 * `["string", "null"]` (unlike Knowledge/Document which are required strings).
 * The mapper tolerates both `null` and `''` as "no more pages".
 */

/** Wire status enum (server may also return EXPIRED). */
export type ApiKeyStatusDto = 'ACTIVE' | 'DISABLED' | 'REVOKED' | 'EXPIRED';

/** GET .../api-keys/{apiKeyId} | POST disable/enable/revoke result payload. */
export interface ApiKeyResponseDto {
  /** Decimal-string id; never a JS number. */
  readonly apiKeyId: string;
  readonly knowledgeBaseId: string;
  readonly name: string;
  readonly status: ApiKeyStatusDto;
  /** Searchable prefix; NEVER the full secret. */
  readonly keyPrefix: string;
  /** ISO-8601 UTC. */
  readonly createdAt: string;
  /** ISO-8601 UTC or null (no expiry). */
  readonly expiresAt: string | null;
}

/** GET .../api-keys result payload (paged; nextPageToken may be null). */
export interface ApiKeyListResponseDto {
  readonly items: ReadonlyArray<ApiKeyResponseDto>;
  /** Null OR empty string signals "no more pages" (ApiKeyListResponse allows null). */
  readonly nextPageToken: string | null;
}

/** POST .../api-keys | POST .../rotate result payload (completeKey one-time). */
export interface CreatedApiKeyResponseDto {
  readonly apiKeyId: string;
  readonly knowledgeBaseId: string;
  readonly name: string;
  /** Complete key — shown ONCE in the modal, then purged. Never cached. */
  readonly completeKey: string;
  readonly expiresAt: string | null;
}

/** POST .../api-keys request body. Name length 1..64; ttlSeconds 0..31536000. */
export interface CreateApiKeyRequestDto {
  readonly name: string;
  readonly ttlSeconds?: number;
}
