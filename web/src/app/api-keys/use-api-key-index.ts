/**
 * API Keys index ViewModel — standalone aggregation page.
 *
 * Aggregates API Keys across ALL of the user's KnowledgeBases. There is NO
 * server-side global search/sort endpoint (plan_22 §22.6 non-goal), so this
 * ViewModel:
 *   1. Loads the COMPLETE KB list by walking all pages in a single query
 *      function (a dedicated `listAllKnowledgeBases` that iterates pageToken
 *      until the server returns an empty token).
 *   2. Fetches each KB's keys through a worker pool capped at concurrency 4
 *      (per plan_22 §22.6 acceptance).
 *   3. Surfaces partial failure: a single KB whose keys fetch fails does NOT
 *      abort the others — successful KBs' keys are rendered alongside a
 *      non-blocking error note for the failed KB(s).
 *
 * The aggregation does NOT cache the per-KB key lists in TanStack Query
 * (they're transient for this page); only the KB list goes through the query
 * cache.
 *
 * Implementation note on React hooks: the pool work is asynchronous, so the
 * `setState` calls that store its results happen AFTER an `await` (never
 * synchronously in an effect body), avoiding the cascading-render anti-pattern
 * flagged by `react-hooks/set-state-in-effect`.
 */
import { useCallback, useEffect, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { consoleKeys } from '@services/http/query-keys';
import {
  listKnowledgeBases,
  type KnowledgeServiceDeps,
} from '@app/knowledge/knowledge-service';
import { defaultKnowledgeDeps } from '@app/knowledge/default-deps';
import { listApiKeys, type ApiKeyServiceDeps } from './api-key-service';
import { defaultApiKeyDeps } from './default-deps';
import { runWithPool, type PoolOutcome } from './worker-pool';
import type { ApiKeyItem } from '@features/api-keys/model/mapper';
import type { KnowledgeBaseLite } from './types';

/** Hard concurrency cap per acceptance ("聚合并发最大 4"). */
const DEFAULT_CONCURRENCY = 4;
/** Page size used when walking the KB list to minimise round-trips. */
const KB_PAGE_SIZE = 100;

/**
 * Walk ALL KB pages for the tenant and return a flat list of {id, name}.
 * Stops when the server returns an empty nextPageToken.
 */
async function listAllKnowledgeBases(
  deps: KnowledgeServiceDeps,
  tenantId: string,
): Promise<ReadonlyArray<KnowledgeBaseLite>> {
  const out: KnowledgeBaseLite[] = [];
  let token = '';
  do {
    const page = await listKnowledgeBases(deps, tenantId, token, KB_PAGE_SIZE);
    for (const kb of page.items) {
      out.push({ id: kb.id, name: kb.name });
    }
    token = page.nextPageToken;
  } while (token && token.length > 0);
  return out;
}

/** A KB row in the aggregation result, paired with its keys or an error. */
export interface KbKeyBucket {
  readonly knowledgeBase: KnowledgeBaseLite;
  readonly keys: ReadonlyArray<ApiKeyItem>;
  /** Set when this KB's keys fetch failed (partial-failure surface). */
  readonly errorMessage: string | null;
}

export interface ApiKeyIndexViewModel {
  /** Stable async status for the View. */
  readonly status: 'loading' | 'empty' | 'error' | 'ready';
  /** All keys across all KBs (flattened). Empty when no keys anywhere. */
  readonly items: ReadonlyArray<ApiKeyItem>;
  /** Per-KB buckets so the View can render KB backlinks and partial failures. */
  readonly buckets: ReadonlyArray<KbKeyBucket>;
  /** KBs whose keys fetch failed (non-blocking; successful KBs still render). */
  readonly failedKnowledgeBaseIds: ReadonlyArray<string>;
  /** Error message for the KB list itself (blocking). */
  readonly errorMessage: string | null;
  /** Re-run the full aggregation (KB list + per-KB keys). */
  refetch(): Promise<void>;
}

/**
 * Build the standalone API Keys index ViewModel. Loads the complete KB list
 * (all pages) via a single TanStack Query, then runs the per-KB keys fetches
 * through a worker pool capped at {@link concurrency}.
 */
export function useApiKeyIndex(options: {
  readonly tenantId: string;
  readonly apiKeyDeps?: ApiKeyServiceDeps;
  readonly knowledgeDeps?: KnowledgeServiceDeps;
  readonly concurrency?: number;
}): ApiKeyIndexViewModel {
  const apiKeyDeps = options.apiKeyDeps ?? defaultApiKeyDeps;
  const knowledgeDeps = options.knowledgeDeps ?? defaultKnowledgeDeps;
  const concurrency = options.concurrency ?? DEFAULT_CONCURRENCY;
  const { tenantId } = options;
  const queryClient = useQueryClient();

  // Single query that walks all KB pages internally.
  const kbQuery = useQuery({
    queryKey: consoleKeys.knowledge.list(tenantId, '__all__'),
    queryFn: () => listAllKnowledgeBases(knowledgeDeps, tenantId),
    enabled: tenantId.length > 0,
  });

  const [buckets, setBuckets] = useState<ReadonlyArray<KbKeyBucket>>([]);
  // Track the KB list identity we last aggregated against so we re-run only
  // when the KB list changes (not on every render).
  const [aggPending, setAggPending] = useState<boolean>(true);
  const lastKbKeyRef = useRef<unknown>(null);

  // Run the per-KB keys aggregation through the worker pool. All setState
  // calls that store RESULTS happen AFTER `await runWithPool(...)`
  // (asynchronous), so they do not trigger the cascading-render anti-pattern.
  // The one synchronous `setAggPending(true)` below flags the aggregation as
  // in-flight before the async work begins; this is structurally necessary
  // because the pool cannot report progress synchronously, and is suppressed
  // from the lint rule for that reason.
  useEffect(() => {
    const data = kbQuery.data;
    if (data === undefined || kbQuery.isPending || kbQuery.isError) {
      return;
    }
    // Avoid re-running the pool for the same KB list identity.
    if (lastKbKeyRef.current === data) {
      return;
    }
    lastKbKeyRef.current = data;
    let cancelled = false;
    // Flag the aggregation as in-flight before the async pool starts so the
    // status reads "loading" instead of momentarily "empty".
    setAggPending(true);
    const tasks = data.map(
      (kb) => async () => {
        const collected: ApiKeyItem[] = [];
        let token = '';
        do {
          const page = await listApiKeys(apiKeyDeps, tenantId, kb.id, token, KB_PAGE_SIZE);
          collected.push(...page.items);
          token = page.nextPageToken;
        } while (token && token.length > 0);
        return collected;
      },
    );
    void (async () => {
      const outcomes: ReadonlyArray<PoolOutcome<ReadonlyArray<ApiKeyItem>>> = await runWithPool(
        tasks,
        concurrency,
      );
      if (cancelled) return;
      const newBuckets: KbKeyBucket[] = data.map((kb, i) => {
        const outcome = outcomes[i]!;
        if (outcome.ok) {
          return { knowledgeBase: kb, keys: outcome.value, errorMessage: null };
        }
        const err = outcome.error;
        const message = err instanceof Error ? err.message : '该知识库的密钥加载失败';
        return { knowledgeBase: kb, keys: [], errorMessage: message };
      });
      setBuckets(newBuckets);
      setAggPending(false);
    })();
    return () => {
      cancelled = true;
    };
  }, [kbQuery.data, kbQuery.isPending, kbQuery.isError, apiKeyDeps, tenantId, concurrency]);

  const refetch = useCallback(async () => {
    await queryClient.invalidateQueries({ queryKey: consoleKeys.knowledge.all() });
  }, [queryClient]);

  const allItems: ApiKeyItem[] = buckets.flatMap((b) => b.keys);
  const failedKbIds = buckets
    .filter((b) => b.errorMessage !== null)
    .map((b) => b.knowledgeBase.id);

  const kbListError = kbQuery.isError
    ? kbQuery.error instanceof Error
      ? kbQuery.error.message
      : '加载知识库失败'
    : null;

  const status: ApiKeyIndexViewModel['status'] = kbQuery.isPending
    ? 'loading'
    : kbQuery.isError
      ? 'error'
      : aggPending
        ? 'loading'
        : allItems.length === 0
          ? 'empty'
          : 'ready';

  return {
    status,
    items: allItems,
    buckets,
    failedKnowledgeBaseIds: failedKbIds,
    errorMessage: kbListError,
    refetch,
  };
}
