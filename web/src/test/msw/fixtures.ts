/**
 * Deterministic MSW fixtures for the Console/Open API envelopes.
 *
 * Security (web/constraints/api-client.md §4 / test-workflow.md §4):
 *  - NO real secrets. Use placeholders like `<PLACEHOLDER_ACCESS_JWT>` matching
 *    the OpenAPI examples so secret-scanners cannot match a real token.
 *  - Field-level `rejectedValue` is always null — even in tests we never build
 *    fixtures that would leak a password echo.
 *  - These fixtures are reusable: feature tests in 22.3+ import and extend.
 */
import type { Envelope, ErrorDetailDto } from '@services/http/types';

/** Placeholder JWT matching OpenAPI examples; never a real key. */
export const PLACEHOLDER_ACCESS_JWT = '<PLACEHOLDER_ACCESS_JWT>';
export const PLACEHOLDER_NEW_JWT = '<PLACEHOLDER_NEW_JWT>';
/** Placeholder complete API Key matching OpenAPI examples. */
export const PLACEHOLDER_COMPLETE_KEY = '<PLACEHOLDER_COMPLETE_KEY>';

/** Trace IDs are safe to surface; use a fixed value for assertions. */
export const FIXED_TRACE_ID = '9c1f-fix-trace';

/** Build a success envelope. */
export function ok<T>(result: T): Envelope & { result: T } {
  return { success: true, code: 0, result };
}

/** Build an error envelope with the given detail. */
export function err(code: number, detail: Partial<ErrorDetailDto> = {}): Envelope {
  const full: ErrorDetailDto = {
    message: 'Server error',
    traceId: FIXED_TRACE_ID,
    reason: 'INTERNAL_ERROR',
    retryable: false,
    fieldErrors: [],
    ...detail,
  };
  return { success: false, code, result: full };
}

/** Standard auth response shape (register/login/refresh success body). */
export function authResponse(
  overrides: {
    accessToken?: string;
    defaultTenant?: { tenantId: string; name: string; role: 'OWNER' | 'MEMBER' } | null;
  } = {},
) {
  return {
    accessToken: overrides.accessToken ?? PLACEHOLDER_ACCESS_JWT,
    accessExpiresAt: '2026-07-02T12:00:00Z',
    user: { userId: '1001', nickname: 'alice' },
    defaultTenant: overrides.defaultTenant === undefined ? null : overrides.defaultTenant,
  };
}

/** Tenant summary fixture used across Knowledge/Membership/API-key tests. */
export const TENANT_FIXTURE = {
  tenantId: '2001',
  name: 'alice 的默认租户',
  role: 'OWNER' as const,
};

/** KnowledgeBase fixture aligned to OpenAPI KnowledgeBaseResponse. */
export const KNOWLEDGE_BASE_FIXTURE = {
  knowledgeBaseId: '3001',
  tenantId: '2001',
  name: '产品文档',
  apiKeyReady: true,
  createdAt: '2026-07-02T09:00:00Z',
  updatedAt: '2026-07-02T09:00:00Z',
};
