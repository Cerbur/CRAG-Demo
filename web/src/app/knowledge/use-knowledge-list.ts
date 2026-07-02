/**
 * Knowledge list ViewModel.
 *
 * Uses TanStack Query to fetch the tenant's KnowledgeBases with pageToken
 * pagination. Tracks pageToken history so the user can go Back, not just Next.
 * The current page's items and the next/previous cursor availability are
 * surfaced to the View via the returned ViewModel.
 *
 * Pagination design (per MANIFEST): Previous/Next via pageToken, NOT numbered
 * total pages. `nextPageToken===''` from the server means no more pages.
 *
 * The list query is NOT polling — list refresh happens on manual refetch or on
 * create-mutation invalidation.
 */
import { useCallback, useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { consoleKeys } from '@services/http/query-keys';
import type { KnowledgeBase } from '@features/knowledge/model/mapper';
import {
  listKnowledgeBases,
  type KnowledgeServiceDeps,
} from './knowledge-service';
import { defaultKnowledgeDeps } from './default-deps';

export interface KnowledgeListViewModel {
  /** Stable async status for the View (loading/empty/error/ready). */
  readonly status: 'loading' | 'empty' | 'error' | 'ready';
  /** Items on the current page (already mapped to domain Model). */
  readonly items: ReadonlyArray<KnowledgeBase>;
  /** Error message surfaced to the View; only set when status==='error'. */
  readonly errorMessage: string | null;
  /** True when a Next page is available (server returned a non-empty token). */
  readonly hasNextPage: boolean;
  /** True when the user is past the first page (Back is available). */
  readonly hasPreviousPage: boolean;
  /** Advance to the next page. No-op when !hasNextPage. */
  gotoNextPage(): void;
  /** Return to the previous page. No-op when !hasPreviousPage. */
  gotoPreviousPage(): void;
  /** Re-fetch the current page (e.g. after an error). */
  refetch(): Promise<void>;
}

/**
 * Build the Knowledge list ViewModel. `tenantId` is required; the View
 * typically reads it from the active AuthSession.
 */
export function useKnowledgeList(options: {
  readonly tenantId: string;
  readonly deps?: KnowledgeServiceDeps;
  readonly pageSize?: number;
}): KnowledgeListViewModel {
  const deps = options.deps ?? defaultKnowledgeDeps;
  const pageSize = options.pageSize ?? 20;

  // pageToken history. Index 0 is the first page (''); the current page is the
  // last entry. Going Next pushes the returned nextPageToken; going Back pops
  // the last entry (returning to the previous cursor).
  const [history, setHistory] = useState<ReadonlyArray<string>>(['']);
  const currentToken = history[history.length - 1] ?? '';

  const queryKey = consoleKeys.knowledge.list(options.tenantId, currentToken);

  const query = useQuery({
    queryKey,
    queryFn: () => listKnowledgeBases(deps, options.tenantId, currentToken, pageSize),
    enabled: options.tenantId.length > 0,
  });

  const nextPageToken = query.data?.nextPageToken ?? '';
  const hasNextPage = nextPageToken.length > 0;
  const hasPreviousPage = history.length > 1;

  const gotoNextPage = useCallback(() => {
    if (!hasNextPage) return;
    setHistory((prev) => [...prev, nextPageToken]);
  }, [hasNextPage, nextPageToken]);

  const gotoPreviousPage = useCallback(() => {
    if (!hasPreviousPage) return;
    setHistory((prev) => prev.slice(0, -1));
  }, [hasPreviousPage]);

  const refetch = useCallback(async () => {
    await query.refetch();
  }, [query]);

  const status: KnowledgeListViewModel['status'] = query.isPending
    ? 'loading'
    : query.isError
      ? 'error'
      : (query.data?.items.length ?? 0) === 0
        ? 'empty'
        : 'ready';

  const errorMessage = query.isError
    ? (query.error instanceof Error ? query.error.message : 'Failed to load knowledge bases')
    : null;

  const items = useMemo(() => query.data?.items ?? [], [query.data]);

  return {
    status,
    items,
    errorMessage,
    hasNextPage,
    hasPreviousPage,
    gotoNextPage,
    gotoPreviousPage,
    refetch,
  };
}
