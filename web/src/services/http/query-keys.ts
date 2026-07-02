/**
 * TanStack Query key factory for all Web Console features.
 *
 * Centralising keys in services/http keeps them out of feature internals and
 * lets the Console client's refresh-failure path (which clears the Query cache
 * via 22.3's bootstrap) reference stable key prefixes without crossing feature
 * boundaries.
 *
 * Convention: every key starts with the API surface (`'console'` or `'open'`),
 * then the resource, then discriminators. Page tokens are appended last so
 * `queryClient.invalidateQueries({ queryKey: consoleKeys.knowledge.all() })`
 * invalidates every Knowledge query regardless of pagination.
 */

export const consoleKeys = {
  /** Top-level Console scope; use to invalidate everything Console-owned. */
  all(): ReadonlyArray<string> {
    return ['console'];
  },
  tenants: {
    all(): ReadonlyArray<string> {
      return ['console', 'tenants'];
    },
    list(pageToken: string = ''): ReadonlyArray<string> {
      return ['console', 'tenants', 'list', pageToken];
    },
  },
  members: {
    all(): ReadonlyArray<string> {
      return ['console', 'members'];
    },
    list(tenantId: string, pageToken: string = ''): ReadonlyArray<string> {
      return ['console', 'members', 'list', tenantId, pageToken];
    },
  },
  knowledge: {
    all(): ReadonlyArray<string> {
      return ['console', 'knowledge'];
    },
    list(tenantId: string, pageToken: string = ''): ReadonlyArray<string> {
      return ['console', 'knowledge', 'list', tenantId, pageToken];
    },
    detail(tenantId: string, knowledgeBaseId: string): ReadonlyArray<string> {
      return ['console', 'knowledge', 'detail', tenantId, knowledgeBaseId];
    },
  },
  documents: {
    all(): ReadonlyArray<string> {
      return ['console', 'documents'];
    },
    list(tenantId: string, knowledgeBaseId: string, pageToken: string = ''): ReadonlyArray<string> {
      return ['console', 'documents', 'list', tenantId, knowledgeBaseId, pageToken];
    },
    detail(tenantId: string, knowledgeBaseId: string, docId: string): ReadonlyArray<string> {
      return ['console', 'documents', 'detail', tenantId, knowledgeBaseId, docId];
    },
  },
  apiKeys: {
    all(): ReadonlyArray<string> {
      return ['console', 'api-keys'];
    },
    list(tenantId: string, knowledgeBaseId: string, pageToken: string = ''): ReadonlyArray<string> {
      return ['console', 'api-keys', 'list', tenantId, knowledgeBaseId, pageToken];
    },
    detail(tenantId: string, knowledgeBaseId: string, apiKeyId: string): ReadonlyArray<string> {
      return ['console', 'api-keys', 'detail', tenantId, knowledgeBaseId, apiKeyId];
    },
  },
} as const;

export const openKeys = {
  /** Top-level Open scope. Open results are per-session and never cached long. */
  all(): ReadonlyArray<string> {
    return ['open'];
  },
  query(sessionKey: string): ReadonlyArray<string> {
    return ['open', 'query', sessionKey];
  },
} as const;
