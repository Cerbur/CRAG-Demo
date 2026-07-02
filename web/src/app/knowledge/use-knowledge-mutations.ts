/**
 * Knowledge create Mutation.
 *
 * Calls {@link createKnowledgeBase} and on success invalidates the Knowledge
 * list query cache so the next list render reflects the new KB. The created KB
 * is returned so the View can navigate to its detail route — including the
 * partial-success case (`apiKeyReady=false`), which is NOT an error.
 *
 * `completeKey`-style secrets are not involved here (those belong to 22.6 API
 * keys); only the KB name is sent.
 */
import { useCallback } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { consoleKeys } from '@services/http/query-keys';
import type { KnowledgeBase } from '@features/knowledge/model/mapper';
import {
  createKnowledgeBase,
  type KnowledgeServiceDeps,
} from './knowledge-service';
import { defaultKnowledgeDeps } from './default-deps';

export interface UseCreateKnowledgeBaseResult {
  /** Trigger a create. Returns the created KB on success; throws on error. */
  createKnowledgeBase(name: string): Promise<KnowledgeBase>;
  /** True while the POST is in flight. */
  readonly isPending: boolean;
  /** Error from the most recent attempt (cleared on a new attempt). */
  readonly error: unknown;
}

/**
 * Build a create-KnowledgeBase mutation. The View typically navigates to the
 * new KB's detail route in the `onSuccess` callback it owns.
 */
export function useCreateKnowledgeBase(options?: {
  readonly deps?: KnowledgeServiceDeps;
  readonly tenantId?: string;
}): UseCreateKnowledgeBaseResult {
  const deps = options?.deps ?? defaultKnowledgeDeps;
  const tenantId = options?.tenantId ?? '';
  const queryClient = useQueryClient();

  const mutation = useMutation({
    mutationFn: (name: string) => createKnowledgeBase(deps, tenantId, name),
    onSuccess: () => {
      // Invalidate the whole knowledge list family so the new KB shows up
      // regardless of the current page cursor.
      void queryClient.invalidateQueries({ queryKey: consoleKeys.knowledge.all() });
    },
  });

  const create = useCallback(
    (name: string) => mutation.mutateAsync(name),
    [mutation],
  );

  return {
    createKnowledgeBase: create,
    isPending: mutation.isPending,
    error: mutation.error,
  };
}
