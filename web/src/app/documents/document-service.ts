/**
 * Document orchestration service.
 *
 * Lives under app/documents (NOT features/documents) because it must import the
 * Console client, and the architecture test forbids any `features/**` file from
 * importing `services/http`. ViewModels in pages call these commands and
 * translate thrown {@link ApiErrorException} into UI state via the shared
 * `ApiError` pipeline.
 *
 * All Document URLs are tenant+KB-scoped:
 *   /console-api/api/v1/tenants/{tenantId}/knowledge-bases/{knowledgeBaseId}/documents[/{docId}[/ingestion/retry]]
 *
 * Upload uses multipart/form-data. The transport passes FormData through
 * verbatim (no JSON serialisation, no manual Content-Type header — the browser
 * sets the multipart boundary). See transport.ts `form` field handling.
 *
 * IDs are decimal strings on the wire and stay as strings through the mapper.
 */
import type { HttpClient } from '@services/http/console-client';
import {
  mapDocumentDto,
  mapDocumentListDto,
  type DocumentItem,
  type DocumentPage,
} from '@features/documents/model/mapper';

/** Injectable dependencies. Tests pass an isolated client. */
export interface DocumentServiceDeps {
  readonly client: HttpClient;
}

/** Build the tenant+KB-scoped document collection path. */
function collectionPath(tenantId: string, knowledgeBaseId: string): string {
  return `/console-api/api/v1/tenants/${tenantId}/knowledge-bases/${knowledgeBaseId}/documents`;
}

/** Build the tenant+KB+doc-scoped item path. */
function itemPath(tenantId: string, knowledgeBaseId: string, docId: string): string {
  return `${collectionPath(tenantId, knowledgeBaseId)}/${docId}`;
}

/** Build the retry path. */
function retryPath(tenantId: string, knowledgeBaseId: string, docId: string): string {
  return `${itemPath(tenantId, knowledgeBaseId, docId)}/ingestion/retry`;
}

/**
 * List Documents for a KB. Pass `pageToken=''` for the first page; the
 * response's `nextPageToken===''` signals end-of-list.
 */
export async function listDocuments(
  deps: DocumentServiceDeps,
  tenantId: string,
  knowledgeBaseId: string,
  pageToken: string = '',
  pageSize?: number,
): Promise<DocumentPage> {
  const query: Record<string, string | number> = {};
  if (pageToken) query['pageToken'] = pageToken;
  if (pageSize !== undefined) query['pageSize'] = pageSize;
  const result = await deps.client.request<unknown>({
    method: 'GET',
    path: collectionPath(tenantId, knowledgeBaseId),
    ...(Object.keys(query).length > 0 ? { query } : {}),
  });
  return mapDocumentListDto(result);
}

/**
 * Upload a single file via multipart/form-data. Returns the server's PENDING
 * projection (HTTP 202). The caller (ViewModel) invalidates the list query so
 * the new document appears.
 *
 * The transport must NOT JSON-serialise FormData nor set Content-Type manually;
 * the browser sets the multipart boundary. We pass `form` (not `body`).
 */
export async function uploadDocument(
  deps: DocumentServiceDeps,
  tenantId: string,
  knowledgeBaseId: string,
  file: File,
): Promise<DocumentItem> {
  const form = new FormData();
  form.append('file', file);
  const result = await deps.client.request<unknown>({
    method: 'POST',
    path: collectionPath(tenantId, knowledgeBaseId),
    form,
  });
  return mapDocumentDto(result);
}

/** Get a single Document by id. Used for polling a specific document. */
export async function getDocument(
  deps: DocumentServiceDeps,
  tenantId: string,
  knowledgeBaseId: string,
  docId: string,
): Promise<DocumentItem> {
  const result = await deps.client.request<unknown>({
    method: 'GET',
    path: itemPath(tenantId, knowledgeBaseId, docId),
  });
  return mapDocumentDto(result);
}

/**
 * Retry a FAILED+retryable Document. On success returns the new PENDING
 * projection (new operationVersion). On 409 INGESTION_RETRY_NOT_ALLOWED the
 * transport throws an ApiErrorException carrying the server's safe message; the
 * ViewModel surfaces it without auto-retrying.
 */
export async function retryDocument(
  deps: DocumentServiceDeps,
  tenantId: string,
  knowledgeBaseId: string,
  docId: string,
): Promise<DocumentItem> {
  const result = await deps.client.request<unknown>({
    method: 'POST',
    path: retryPath(tenantId, knowledgeBaseId, docId),
  });
  return mapDocumentDto(result);
}
