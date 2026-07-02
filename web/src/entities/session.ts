/**
 * Authentication session domain model.
 *
 * The Web Console keeps Access Token ONLY in memory (web/constraints/api-client.md
 * §2). Refresh Token is HttpOnly cookie, never visible to JS. This module is the
 * pure domain type shared by the app/session orchestration, ViewModels and the
 * UI shell; it deliberately contains NO persistence behaviour — the persistence
 * contract is {@link ../services/http/session-store}.
 *
 * A `null` AuthSession means the user is anonymous; a populated one means the
 * Console client holds a live Access JWT in memory.
 *
 * 22.3 task contract: `{ userId, nickname, tenantId, role }`. The accessToken
 * itself is NOT carried here — it lives in the SessionStore. The tenantId/role
 * come from either the register response's `defaultTenant` (non-null) or from
 * the first entry of `GET /api/v1/tenants` after login/refresh.
 */

export type TenantRole = 'OWNER' | 'MEMBER';

/**
 * Resolved auth session consumed by ViewModels, the protected route and the
 * shell account menu. Does NOT carry the raw JWT.
 */
export interface AuthSession {
  readonly userId: string;
  readonly nickname: string;
  readonly tenantId: string;
  readonly role: TenantRole;
}
