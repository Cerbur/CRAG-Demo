/**
 * Authentication session domain model.
 *
 * The Web Console keeps Access Token ONLY in memory (web/constraints/api-client.md
 * §2). Refresh Token is HttpOnly cookie, never visible to JS. This module is the
 * pure domain type shared by the transport layer, ViewModel and (in 22.3) the
 * session bootstrap; it deliberately contains NO persistence behaviour — the
 * persistence contract is {@link ../services/http/session-store}.
 *
 * A `null` AuthSession means the user is anonymous; a populated one means the
 * Console client holds a live Access JWT in memory.
 */

/**
 * User projection returned by /auth/me and embedded in auth responses.
 * Mirrors the OpenAPI `UserResponse` schema (userId + nickname).
 */
export interface AuthUser {
  readonly userId: string;
  readonly nickname: string;
}

/**
 * Tenant summary embedded in register responses and the tenants list.
 * Mirrors the OpenAPI `TenantSummaryResponse`.
 */
export interface AuthTenantSummary {
  readonly tenantId: string;
  readonly name: string;
  readonly role: 'OWNER' | 'MEMBER';
}

/**
 * Resolved auth session. The accessToken itself lives in the SessionStore;
 * this type is the public, surface representation consumed by ViewModels
 * and the UI shell. It does NOT carry the raw JWT.
 */
export interface AuthSession {
  readonly user: AuthUser;
  /** Present right after register; null after login/refresh (see OpenAPI §register). */
  readonly defaultTenant: AuthTenantSummary | null;
  /** Access JWT expiry as RFC 3339 UTC, used by ViewModels to anticipate refresh. */
  readonly accessExpiresAt: string;
}
