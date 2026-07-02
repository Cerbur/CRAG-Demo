/**
 * API Key orchestration service.
 *
 * Lives under app/api-keys (NOT features/api-keys) because it must import the
 * Console client, and the architecture test forbids any `features/**` file from
 * importing `services/http`. ViewModels call these commands and translate
 * thrown {@link ApiErrorException} into UI state via the shared `ApiError`
 * pipeline.
 *
 * All API Key URLs are tenant+KB-scoped:
 *   /console-api/api/v1/tenants/{tenantId}/knowledge-bases/{knowledgeBaseId}/api-keys[/{apiKeyId}[/disable|enable|rotate|revoke]]
 *
 * IDs are decimal strings on the wire and stay as strings through the mapper.
 */
import type { HttpClient } from '@services/http/console-client';
import {
  mapApiKeyDto,
  mapApiKeyListDto,
  mapCreatedApiKeyDto,
  toCreateApiKeyRequest,
  type ApiKeyItem,
  type ApiKeyPage,
  type CreatedApiKey,
} from '@features/api-keys/model/mapper';

/** Injectable dependencies. Tests pass an isolated client. */
export interface ApiKeyServiceDeps {
  readonly client: HttpClient;
}

/** Build the tenant+KB-scoped api-key collection path. */
function collectionPath(tenantId: string, knowledgeBaseId: string): string {
  return `/console-api/api/v1/tenants/${tenantId}/knowledge-bases/${knowledgeBaseId}/api-keys`;
}

/** Build the tenant+KB+key-scoped item path. */
function itemPath(tenantId: string, knowledgeBaseId: string, apiKeyId: string): string {
  return `${collectionPath(tenantId, knowledgeBaseId)}/${apiKeyId}`;
}

/**
 * List API Keys for a KB. Pass `pageToken=''` for the first page; the
 * response's `nextPageToken` is null OR '' to signal end-of-list (the OpenAPI
 * contract allows null for keys, unlike Knowledge/Document).
 */
export async function listApiKeys(
  deps: ApiKeyServiceDeps,
  tenantId: string,
  knowledgeBaseId: string,
  pageToken: string = '',
  pageSize?: number,
): Promise<ApiKeyPage> {
  const query: Record<string, string | number> = {};
  if (pageToken) query['pageToken'] = pageToken;
  if (pageSize !== undefined) query['pageSize'] = pageSize;
  const result = await deps.client.request<unknown>({
    method: 'GET',
    path: collectionPath(tenantId, knowledgeBaseId),
    ...(Object.keys(query).length > 0 ? { query } : {}),
  });
  return mapApiKeyListDto(result);
}

/**
 * Create an API Key. Returns HTTP 201 with CreatedApiKeyResponse — the
 * `completeKey` is shown ONCE in the modal, then must be purged. The caller
 * (ViewModel) is responsible for keeping it out of the Query cache.
 */
export async function createApiKey(
  deps: ApiKeyServiceDeps,
  tenantId: string,
  knowledgeBaseId: string,
  name: string,
  ttlSeconds?: number,
): Promise<CreatedApiKey> {
  const body = toCreateApiKeyRequest(name, ttlSeconds);
  const result = await deps.client.request<unknown>({
    method: 'POST',
    path: collectionPath(tenantId, knowledgeBaseId),
    body,
  });
  return mapCreatedApiKeyDto(result);
}

/** Get a single API Key by id (prefix only — never the complete secret). */
export async function getApiKey(
  deps: ApiKeyServiceDeps,
  tenantId: string,
  knowledgeBaseId: string,
  apiKeyId: string,
): Promise<ApiKeyItem> {
  const result = await deps.client.request<unknown>({
    method: 'GET',
    path: itemPath(tenantId, knowledgeBaseId, apiKeyId),
  });
  return mapApiKeyDto(result);
}

/** Disable an ACTIVE key → DISABLED. 409 if not ACTIVE. */
export async function disableApiKey(
  deps: ApiKeyServiceDeps,
  tenantId: string,
  knowledgeBaseId: string,
  apiKeyId: string,
): Promise<ApiKeyItem> {
  const result = await deps.client.request<unknown>({
    method: 'POST',
    path: `${itemPath(tenantId, knowledgeBaseId, apiKeyId)}/disable`,
  });
  return mapApiKeyDto(result);
}

/** Enable a DISABLED key → ACTIVE. 409 if not DISABLED. */
export async function enableApiKey(
  deps: ApiKeyServiceDeps,
  tenantId: string,
  knowledgeBaseId: string,
  apiKeyId: string,
): Promise<ApiKeyItem> {
  const result = await deps.client.request<unknown>({
    method: 'POST',
    path: `${itemPath(tenantId, knowledgeBaseId, apiKeyId)}/enable`,
  });
  return mapApiKeyDto(result);
}

/**
 * Rotate an ACTIVE key → returns a NEW CreatedApiKeyResponse with a fresh
 * one-time completeKey. 409 if not ACTIVE. The caller must purge the new
 * completeKey after the modal closes.
 */
export async function rotateApiKey(
  deps: ApiKeyServiceDeps,
  tenantId: string,
  knowledgeBaseId: string,
  apiKeyId: string,
): Promise<CreatedApiKey> {
  const result = await deps.client.request<unknown>({
    method: 'POST',
    path: `${itemPath(tenantId, knowledgeBaseId, apiKeyId)}/rotate`,
  });
  return mapCreatedApiKeyDto(result);
}

/** Revoke a key (ACTIVE or DISABLED) → REVOKED. 409 if already REVOKED. */
export async function revokeApiKey(
  deps: ApiKeyServiceDeps,
  tenantId: string,
  knowledgeBaseId: string,
  apiKeyId: string,
): Promise<ApiKeyItem> {
  const result = await deps.client.request<unknown>({
    method: 'POST',
    path: `${itemPath(tenantId, knowledgeBaseId, apiKeyId)}/revoke`,
  });
  return mapApiKeyDto(result);
}
