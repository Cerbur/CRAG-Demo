/**
 * Knowledge detail ViewModel.
 *
 * Fetches a single KnowledgeBase and polls readiness while:
 *   1. `apiKeyReady === false` (partial-success create or scope still catching
 *      up), AND
 *   2. the page is visible (TanStack Query `refetchIntervalInBackground:
 *      false` automatically pauses when the tab is hidden).
 *
 * Polling stops the moment `apiKeyReady === true` (the `refetchInterval`
 * callback returns `false`). Polling also stops on unmount because TanStack
 * Query unsubscribes and stops the interval when the consuming component
 * unmounts — the test in `use-knowledge-detail.test.tsx` proves this by
 * asserting the MSW call count does not increase after unmount.
 *
 * On a 404 (cross-tenant or non-existent KB) the server returns a stable
 * NOT_FOUND error envelope; the ApiError is surfaced to the View as a
 * not-found state.
 */
import { useCallback } from 'react';
import { useQuery } from '@tanstack/react-query';
import { consoleKeys } from '@services/http/query-keys';
import type { KnowledgeBase } from '@features/knowledge/model/mapper';
import {
  getKnowledgeBase,
  type KnowledgeServiceDeps,
} from './knowledge-service';
import { defaultKnowledgeDeps } from './default-deps';

/** Polling cadence for apiKeyReady readiness. */
const READINESS_POLL_INTERVAL_MS = 3_000;

export interface KnowledgeDetailViewModel {
  /** Stable async status for the View. */
  readonly status: 'loading' | 'not-found' | 'error' | 'ready';
  /** Domain KnowledgeBase; only set when status==='ready'. */
  readonly knowledgeBase: KnowledgeBase | null;
  /** True when the KB exists but apiKeyReady is still false. */
  readonly awaitingReadiness: boolean;
  /** Error message for generic errors (not not-found). */
  readonly errorMessage: string | null;
  /** Manually re-trigger the fetch (e.g. retry button). */
  refetch(): Promise<void>;
}

function classifyError(err: unknown): 'not-found' | 'error' {
  // The ApiError carried by ApiErrorException exposes kind; 404/cross-tenant
  // maps to `business` with reason NOT_FOUND, but we treat any 404-class as
  // not-found. The transport maps HTTP 404 → kind 'business'.
  const apiError = (
    err as { readonly apiError?: { readonly kind?: string; readonly message?: string } } | undefined
  )?.apiError;
  if (apiError?.message?.toLowerCase().includes('not found')) return 'not-found';
  // Fall back: any business error with no data is treated as generic error;
  // the View shows the server message.
  return 'error';
}

/**
 * Build the Knowledge detail ViewModel. Polls readiness while the KB is not
 * apiKey-ready and the tab is visible; stops on unmount.
 */
export function useKnowledgeDetail(options: {
  readonly tenantId: string;
  readonly knowledgeBaseId: string;
  readonly deps?: KnowledgeServiceDeps;
  /** Override the readiness poll interval (tests use a short value). */
  readonly pollIntervalMs?: number;
}): KnowledgeDetailViewModel {
  const deps = options.deps ?? defaultKnowledgeDeps;
  const { tenantId, knowledgeBaseId } = options;
  const pollIntervalMs = options.pollIntervalMs ?? READINESS_POLL_INTERVAL_MS;

  const query = useQuery({
    queryKey: consoleKeys.knowledge.detail(tenantId, knowledgeBaseId),
    queryFn: () => getKnowledgeBase(deps, tenantId, knowledgeBaseId),
    enabled: tenantId.length > 0 && knowledgeBaseId.length > 0,
    // Poll while not ready; pause when the tab is hidden; stop when ready.
    refetchInterval: (query) => {
      const data = query.state.data;
      if (data && data.apiKeyReady) return false; // stop polling
      return pollIntervalMs; // keep polling (or start)
    },
    refetchIntervalInBackground: false,
  });

  const refetch = useCallback(async () => {
    await query.refetch();
  }, [query]);

  const data = query.data ?? null;

  let status: KnowledgeDetailViewModel['status'];
  if (query.isPending) {
    status = 'loading';
  } else if (query.isError) {
    status = classifyError(query.error);
  } else if (data) {
    status = 'ready';
  } else {
    status = 'loading';
  }

  const errorMessage =
    query.isError && status === 'error'
      ? (query.error instanceof Error ? query.error.message : 'Failed to load knowledge base')
      : null;

  return {
    status,
    knowledgeBase: data,
    awaitingReadiness: data !== null && !data.apiKeyReady,
    errorMessage,
    refetch,
  };
}
