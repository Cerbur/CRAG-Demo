/**
 * Documents ViewModel.
 *
 * Composes three concerns over the tenant+KB-scoped Document collection:
 *
 *  1. List query with pageToken pagination (mirror of Knowledge list).
 *  2. Active-state polling: while ANY document is PENDING or PROCESSING the
 *     list refetches on a short interval; once every document is terminal
 *     (READY/FAILED) the interval returns false and polling stops. Polling
 *     also stops when the tab is hidden (`refetchIntervalInBackground: false`)
 *     and on unmount (TanStack Query unsubscribes — proven by the unmount
 *     test).
 *  3. Upload mutation (multipart) + Retry mutation with server-error surfacing.
 *
 * Error precedence: backend authoritative. The server's safe message on 413 /
 * 415 / 409 is surfaced to the View via the mutation's `error` field; the
 * client-side `validateUpload` text is only used when the request never reaches
 * the server (e.g. wrong extension caught client-side as a UX pre-check).
 *
 * `validateUpload` runs BEFORE the network call. If it returns invalid, the
 * upload mutation rejects with a synthetic Error carrying the client message
 * and NO request is sent. If the server then rejects with 413/415, the server
 * message is used instead (it appears in the mutation error after the request
 * is actually attempted — but since client validation already short-circuits,
 * the precedence rule is exercised only for cases the client pre-check does
 * not cover, e.g. server-side UTF-8 / anti-virus rejections).
 */
import { useCallback } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { consoleKeys } from '@services/http/query-keys';
import { ApiErrorException } from '@services/http/api-error';
import {
  listDocuments,
  retryDocument,
  uploadDocument,
  type DocumentServiceDeps,
} from './document-service';
import { defaultDocumentDeps } from './default-deps';
import { validateUpload } from '@features/documents/model/validate-upload';
import type { DocumentItem } from '@features/documents/model/mapper';

/** Polling cadence while active (PENDING/PROCESSING) documents exist. */
const ACTIVE_POLL_INTERVAL_MS = 3_000;

/** Default page size for the document list. */
const DEFAULT_PAGE_SIZE = 50;

export interface DocumentsViewModel {
  /** Stable async status for the View. */
  readonly status: 'loading' | 'empty' | 'error' | 'ready';
  /** Items on the current page (mapped to domain Model). */
  readonly items: ReadonlyArray<DocumentItem>;
  /** Error message for the list; only set when status==='error'. */
  readonly errorMessage: string | null;
  /** Re-fetch the current page (e.g. after an error). */
  refetch(): Promise<void>;
  /** Upload mutation. */
  readonly upload: {
    /** Trigger an upload. Validates client-side first; then POSTs multipart. */
    uploadFile(file: File): Promise<DocumentItem>;
    /** True while the POST is in flight. */
    readonly isPending: boolean;
    /** Error from the most recent attempt (server message takes precedence). */
    readonly error: unknown;
    /** Reset mutation state (clear error / isPending). */
    reset(): void;
  };
  /** Retry mutation. */
  readonly retry: {
    /** Trigger a retry. Only call when canRetry(doc) is true. */
    retryDocument(docId: string): Promise<DocumentItem>;
    /** True while the POST is in flight. */
    readonly isPending: boolean;
    /** Error from the most recent attempt (409 message surfaced). */
    readonly error: unknown;
    /** Reset mutation state. */
    reset(): void;
  };
  /** True when retry should be exposed for this document. */
  canRetry(doc: DocumentItem | null | undefined): boolean;
}

/** Extract a safe message from a thrown error (ApiError or generic Error). */
function errorMessageOf(err: unknown): string {
  if (err instanceof ApiErrorException) return err.apiError.message;
  if (err instanceof Error) return err.message;
  return '操作失败';
}

/** True when at least one document is in an active (non-terminal) state. */
function hasActiveDocuments(items: ReadonlyArray<DocumentItem>): boolean {
  return items.some((d) => d.status === 'PENDING' || d.status === 'PROCESSING');
}

/**
 * Optimistically mark a retried document PENDING in the cached list so polling
 * engages immediately. A real backend puts the document back into PENDING when
 * the retry is accepted; without this, polling only restarts after the next
 * list refetch reads PENDING — and if that refetch races ahead of the server
 * state flip the list can stay terminal (FAILED) and never poll again. This
 * also improves UX: the row shows 处理中/待处理 right after the user clicks 重试.
 *
 * Mutates the cache in place via {@link QueryClient.setQueryData}; the
 * subsequent invalidate/refetch reconciles with the authoritative server state.
 */
function optimisticallyMarkRetrying(
  queryClient: ReturnType<typeof useQueryClient>,
  tenantId: string,
  knowledgeBaseId: string,
  retried: DocumentItem,
): void {
  const key = consoleKeys.documents.list(tenantId, knowledgeBaseId, '');
  queryClient.setQueryData<{ items: DocumentItem[]; nextPageToken: string } | undefined>(
    key,
    (existing) => {
      if (!existing) return existing;
      const items = existing.items.map((d) =>
        d.id === retried.id
          ? {
              ...d,
              status: 'PENDING' as const,
              attempt: retried.attempt,
              retryable: false,
              failureMessage: null,
            }
          : d,
      );
      return { ...existing, items };
    },
  );
}

/**
 * Build the Documents ViewModel.
 *
 * @param options.tenantId from AuthSession
 * @param options.knowledgeBaseId from the detail route param
 */
export function useDocuments(options: {
  readonly tenantId: string;
  readonly knowledgeBaseId: string;
  readonly deps?: DocumentServiceDeps;
  readonly pageSize?: number;
  readonly pollIntervalMs?: number;
}): DocumentsViewModel {
  const deps = options.deps ?? defaultDocumentDeps;
  const { tenantId, knowledgeBaseId } = options;
  const pageSize = options.pageSize ?? DEFAULT_PAGE_SIZE;
  const pollIntervalMs = options.pollIntervalMs ?? ACTIVE_POLL_INTERVAL_MS;
  const queryClient = useQueryClient();

  const enabled = tenantId.length > 0 && knowledgeBaseId.length > 0;

  const query = useQuery({
    queryKey: consoleKeys.documents.list(tenantId, knowledgeBaseId, ''),
    queryFn: () => listDocuments(deps, tenantId, knowledgeBaseId, '', pageSize),
    enabled,
    // Poll while any document is active; pause when the tab is hidden; stop
    // when all are terminal.
    refetchInterval: (q) => {
      const data = q.state.data;
      if (data && hasActiveDocuments(data.items)) return pollIntervalMs;
      return false;
    },
    refetchIntervalInBackground: false,
  });

  const refetch = useCallback(async () => {
    await query.refetch();
  }, [query]);

  const uploadMutation = useMutation({
    mutationFn: async (file: File): Promise<DocumentItem> => {
      // UX pre-check. The backend remains authoritative; if this passes but
      // the server returns 413/415, the server message surfaces via the
      // thrown ApiErrorException below.
      const v = validateUpload(file);
      if (!v.valid) {
        throw new Error(v.message);
      }
      return uploadDocument(deps, tenantId, knowledgeBaseId, file);
    },
    onSuccess: () => {
      // Refresh the list so the new PENDING document appears and polling
      // engages. We do NOT optimistically insert — the server's 202 response is
      // the source of truth.
      void queryClient.invalidateQueries({
        queryKey: consoleKeys.documents.all(),
      });
    },
  });

  const retryMutation = useMutation({
    mutationFn: (docId: string) => retryDocument(deps, tenantId, knowledgeBaseId, docId),
    onSuccess: (retried) => {
      // Optimistically mark the document PENDING so the refetchInterval
      // re-engages immediately and the user sees the active state without
      // waiting for the next list refetch. The invalidate that follows
      // reconciles with the authoritative server projection.
      optimisticallyMarkRetrying(queryClient, tenantId, knowledgeBaseId, retried);
      void queryClient.invalidateQueries({
        queryKey: consoleKeys.documents.all(),
      });
    },
  });

  const canRetry = useCallback(
    (doc: DocumentItem | null | undefined): boolean => {
      return !!doc && doc.status === 'FAILED' && doc.retryable;
    },
    [],
  );

  const status: DocumentsViewModel['status'] = !enabled
    ? 'loading'
    : query.isPending
      ? 'loading'
      : query.isError
        ? 'error'
        : (query.data?.items.length ?? 0) === 0
          ? 'empty'
          : 'ready';

  const errorMessage = query.isError
    ? errorMessageOf(query.error)
    : null;

  const items = query.data?.items ?? [];

  const uploadReset = useCallback(() => uploadMutation.reset(), [uploadMutation]);
  const retryReset = useCallback(() => retryMutation.reset(), [retryMutation]);

  return {
    status,
    items,
    errorMessage,
    refetch,
    upload: {
      uploadFile: (file: File) => uploadMutation.mutateAsync(file),
      isPending: uploadMutation.isPending,
      error: uploadMutation.error,
      reset: uploadReset,
    },
    retry: {
      retryDocument: (docId: string) => retryMutation.mutateAsync(docId),
      isPending: retryMutation.isPending,
      error: retryMutation.error,
      reset: retryReset,
    },
    canRetry,
  };
}

/** Re-export for the View (so it does not need to import validateUpload directly). */
export { validateUpload } from '@features/documents/model/validate-upload';
