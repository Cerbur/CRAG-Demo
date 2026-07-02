/**
 * Knowledge orchestration service.
 *
 * Lives under app/knowledge (NOT features/knowledge) because it must import the
 * Console client, and the architecture test forbids any `features/**` file from
 * importing `services/http`. ViewModels in pages call these commands and
 * translate thrown {@link ApiErrorException} into UI state via the shared
 * `mapApiError`/`ApiError` pipeline.
 *
 * All KnowledgeBase URLs are tenant-scoped:
 *   /console-api/api/v1/tenants/{tenantId}/knowledge-bases[/{id}]
 *
 * IDs are decimal strings on the wire and stay as strings through the mapper.
 */
import type { HttpClient } from '@services/http/console-client';
import {
  mapKnowledgeBaseDto,
  mapKnowledgeBaseListDto,
  toCreateKnowledgeBaseRequest,
  type KnowledgeBase,
  type KnowledgePage,
} from '@features/knowledge/model/mapper';

/** Injectable dependencies. Tests pass an isolated client. */
export interface KnowledgeServiceDeps {
  readonly client: HttpClient;
}

/** Build the tenant-scoped collection path. */
function collectionPath(tenantId: string): string {
  return `/console-api/api/v1/tenants/${tenantId}/knowledge-bases`;
}

/** Build the tenant+kb-scoped item path. */
function itemPath(tenantId: string, knowledgeBaseId: string): string {
  return `${collectionPath(tenantId)}/${knowledgeBaseId}`;
}

/**
 * List KnowledgeBases for a tenant. Pass `pageToken=''` for the first page;
 * the response's `nextPageToken===''` signals end-of-list.
 *
 * @param pageSize server-default is used when undefined (server caps at 100).
 */
export async function listKnowledgeBases(
  deps: KnowledgeServiceDeps,
  tenantId: string,
  pageToken: string = '',
  pageSize?: number,
): Promise<KnowledgePage> {
  const query: Record<string, string | number> = {};
  if (pageToken) query['pageToken'] = pageToken;
  if (pageSize !== undefined) query['pageSize'] = pageSize;
  const result = await deps.client.request<unknown>({
    method: 'GET',
    path: collectionPath(tenantId),
    ...(Object.keys(query).length > 0 ? { query } : {}),
  });
  return mapKnowledgeBaseListDto(result);
}

/**
 * Create a KnowledgeBase. Returns HTTP 201 with the new KB. `apiKeyReady` may
 * be `false` (Access EnsureScope partial success) — this is NOT an error; the
 * caller navigates to the new KB's detail regardless, and the detail page polls
 * readiness.
 */
export async function createKnowledgeBase(
  deps: KnowledgeServiceDeps,
  tenantId: string,
  name: string,
): Promise<KnowledgeBase> {
  const body = toCreateKnowledgeBaseRequest(name);
  const result = await deps.client.request<unknown>({
    method: 'POST',
    path: collectionPath(tenantId),
    body,
  });
  return mapKnowledgeBaseDto(result);
}

/** Get a single KnowledgeBase by id. Used by the detail page and polling. */
export async function getKnowledgeBase(
  deps: KnowledgeServiceDeps,
  tenantId: string,
  knowledgeBaseId: string,
): Promise<KnowledgeBase> {
  const result = await deps.client.request<unknown>({
    method: 'GET',
    path: itemPath(tenantId, knowledgeBaseId),
  });
  return mapKnowledgeBaseDto(result);
}
