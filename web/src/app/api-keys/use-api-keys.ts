/**
 * API Keys ViewModel (KB-scoped).
 *
 * Composes the tenant+KB-scoped API Key collection:
 *
 *  1. List query (pageToken pagination, nextPageToken tolerates null/empty).
 *  2. Create / Rotate / Disable / Enable / Revoke mutations.
 *
 * ONE-TIME SECRET HARD RULE (plan_22 §22.6 acceptance):
 *  - The `completeKey` from create/rotate is returned to the caller and stored
 *    ONLY in the returned `secret` object on this ViewModel. It is NEVER placed
 *    into the TanStack Query cache (we invalidate, not setQueryData, after a
 *    create/rotate). When the View calls {@link SecretHolder.clearSecret} the
 *    value is purged from React state and no reference is retained.
 *  - The list query result only ever contains prefix-bearing {@link ApiKeyItem}
 *    objects; the completeKey never flows through the query pipeline.
 *
 * Status action matrix is enforced by {@link allowedApiKeyActions} in the model
 * layer (pure). The View imports it via re-export below.
 */
import { useCallback, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { consoleKeys } from '@services/http/query-keys';
import {
  createApiKey,
  disableApiKey,
  enableApiKey,
  listApiKeys,
  revokeApiKey,
  rotateApiKey,
  type ApiKeyServiceDeps,
} from './api-key-service';
import { defaultApiKeyDeps } from './default-deps';
import type { ApiKeyItem, CreatedApiKey } from '@features/api-keys/model/mapper';
import { allowedApiKeyActions } from '@features/api-keys/model/actions';

/** Default page size for the api-key list. */
const DEFAULT_PAGE_SIZE = 50;

/**
 * Holds the one-time complete key. The View calls {@link clearSecret} when the
 * "Save your API key" modal closes (Done or dismiss). Until then the value
 * lives only in this React state object — never in the Query cache.
 */
export interface SecretHolder {
  /** The current one-time secret (create or rotate result), or null when cleared. */
  readonly secret: CreatedApiKey | null;
  /** Purge the secret from React state. Call on modal close. */
  clearSecret(): void;
}

/** Common shape for the four status-action mutations. */
export interface StatusActionMutation {
  /** Trigger the action. Returns the updated key on success; throws on error. */
  run(apiKeyId: string): Promise<ApiKeyItem>;
  /** True while the POST is in flight. */
  readonly isPending: boolean;
  /** Error from the most recent attempt (409 conflict message surfaced). */
  readonly error: unknown;
  /** Reset mutation state (clear error / isPending). */
  reset(): void;
}

/** Create mutation result (carries the one-time secret holder). */
export interface CreateMutation {
  createKey(name: string, ttlSeconds?: number): Promise<CreatedApiKey>;
  readonly isPending: boolean;
  readonly error: unknown;
  reset(): void;
}

/** Rotate mutation result (carries the one-time secret holder). */
export interface RotateMutation {
  rotateKey(apiKeyId: string): Promise<CreatedApiKey>;
  readonly isPending: boolean;
  readonly error: unknown;
  reset(): void;
}

export interface ApiKeysViewModel {
  /** Stable async status for the View. */
  readonly status: 'loading' | 'empty' | 'error' | 'ready';
  readonly items: ReadonlyArray<ApiKeyItem>;
  readonly errorMessage: string | null;
  refetch(): Promise<void>;
  readonly create: CreateMutation;
  readonly disable: StatusActionMutation;
  readonly enable: StatusActionMutation;
  readonly rotate: RotateMutation;
  readonly revoke: StatusActionMutation;
  /** One-time secret holder. completeKey lives ONLY here, never in cache. */
  readonly secret: SecretHolder;
  /** Re-export of the pure action matrix for the View. */
  readonly allowedActions: typeof allowedApiKeyActions;
}

/**
 * Build the KB-scoped API Keys ViewModel.
 *
 * @param options.tenantId from AuthSession
 * @param options.knowledgeBaseId from the detail route param
 */
export function useApiKeys(options: {
  readonly tenantId: string;
  readonly knowledgeBaseId: string;
  readonly deps?: ApiKeyServiceDeps;
  readonly pageSize?: number;
}): ApiKeysViewModel {
  const deps = options.deps ?? defaultApiKeyDeps;
  const { tenantId, knowledgeBaseId } = options;
  const pageSize = options.pageSize ?? DEFAULT_PAGE_SIZE;
  const queryClient = useQueryClient();

  // One-time secret state. Held in React state (NOT in the Query cache).
  const [secret, setSecret] = useState<CreatedApiKey | null>(null);
  const clearSecret = useCallback(() => setSecret(null), []);

  const enabled = tenantId.length > 0 && knowledgeBaseId.length > 0;

  const query = useQuery({
    queryKey: consoleKeys.apiKeys.list(tenantId, knowledgeBaseId, ''),
    queryFn: () => listApiKeys(deps, tenantId, knowledgeBaseId, '', pageSize),
    enabled,
  });

  const refetch = useCallback(async () => {
    await query.refetch();
  }, [query]);

  // Create: store the one-time secret in React state, then invalidate the list
  // (NOT setQueryData — we must never place completeKey-bearing objects in the
  // cache).
  const createMutation = useMutation({
    mutationFn: (args: { name: string; ttlSeconds?: number }) =>
      createApiKey(deps, tenantId, knowledgeBaseId, args.name, args.ttlSeconds),
    onSuccess: (created) => {
      setSecret(created);
      void queryClient.invalidateQueries({ queryKey: consoleKeys.apiKeys.all() });
    },
  });

  const disableMutation = useMutation({
    mutationFn: (apiKeyId: string) =>
      disableApiKey(deps, tenantId, knowledgeBaseId, apiKeyId),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: consoleKeys.apiKeys.all() });
    },
  });

  const enableMutation = useMutation({
    mutationFn: (apiKeyId: string) =>
      enableApiKey(deps, tenantId, knowledgeBaseId, apiKeyId),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: consoleKeys.apiKeys.all() });
    },
  });

  // Rotate: like create, the result carries a one-time completeKey. Store it in
  // React state for the modal, never in the cache.
  const rotateMutation = useMutation({
    mutationFn: (apiKeyId: string) =>
      rotateApiKey(deps, tenantId, knowledgeBaseId, apiKeyId),
    onSuccess: (rotated) => {
      setSecret(rotated);
      void queryClient.invalidateQueries({ queryKey: consoleKeys.apiKeys.all() });
    },
  });

  const revokeMutation = useMutation({
    mutationFn: (apiKeyId: string) =>
      revokeApiKey(deps, tenantId, knowledgeBaseId, apiKeyId),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: consoleKeys.apiKeys.all() });
    },
  });

  const status: ApiKeysViewModel['status'] = !enabled
    ? 'loading'
    : query.isPending
      ? 'loading'
      : query.isError
        ? 'error'
        : (query.data?.items.length ?? 0) === 0
          ? 'empty'
          : 'ready';

  const errorMessage = query.isError
    ? query.error instanceof Error
      ? query.error.message
      : '加载失败'
    : null;

  const items = query.data?.items ?? [];

  return {
    status,
    items,
    errorMessage,
    refetch,
    create: {
      createKey: (name, ttlSeconds) =>
        ttlSeconds === undefined
          ? createMutation.mutateAsync({ name })
          : createMutation.mutateAsync({ name, ttlSeconds }),
      isPending: createMutation.isPending,
      error: createMutation.error,
      reset: () => createMutation.reset(),
    },
    disable: {
      run: (id: string) => disableMutation.mutateAsync(id),
      isPending: disableMutation.isPending,
      error: disableMutation.error,
      reset: () => disableMutation.reset(),
    },
    enable: {
      run: (id: string) => enableMutation.mutateAsync(id),
      isPending: enableMutation.isPending,
      error: enableMutation.error,
      reset: () => enableMutation.reset(),
    },
    rotate: {
      rotateKey: (id: string) => rotateMutation.mutateAsync(id),
      isPending: rotateMutation.isPending,
      error: rotateMutation.error,
      reset: () => rotateMutation.reset(),
    },
    revoke: {
      run: (id: string) => revokeMutation.mutateAsync(id),
      isPending: revokeMutation.isPending,
      error: revokeMutation.error,
      reset: () => revokeMutation.reset(),
    },
    secret: { secret, clearSecret },
    allowedActions: allowedApiKeyActions,
  };
}

/** Re-export for the View. */
export { allowedApiKeyActions } from '@features/api-keys/model/actions';
