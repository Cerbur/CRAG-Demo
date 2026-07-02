/**
 * Auth DTO shapes mirroring the OpenAPI Console contract.
 *
 * These live under features/auth/model so the mapper and Zod schemas can import
 * them without crossing into services/http (the architecture test forbids any
 * `features/**` file from importing `services/http`). The transport returns the
 * raw `result` payload as `unknown`; the mapper narrows against these types.
 *
 * Source of truth: docs/api/console-api.openapi.yaml — AuthResponse,
 * UserResponse, TenantSummaryResponse, TenantListResponse.
 */

/** POST /api/v1/auth/register | login | refresh result payload. */
export interface AuthResponseDto {
  readonly accessToken: string;
  readonly accessExpiresAt: string;
  readonly user: { readonly userId: string; readonly nickname: string };
  /** Non-null only for register; null for login/refresh per OpenAPI. */
  readonly defaultTenant: TenantSummaryDto | null;
}

/** GET /api/v1/auth/me result payload. */
export interface UserResponseDto {
  readonly userId: string;
  readonly nickname: string;
}

/** GET /api/v1/tenants result payload. */
export interface TenantListResponseDto {
  readonly items: ReadonlyArray<TenantSummaryDto>;
  readonly nextPageToken: string;
}

/** Tenant projection embedded in register response / tenants list. */
export interface TenantSummaryDto {
  readonly tenantId: string;
  readonly name: string;
  readonly role: 'OWNER' | 'MEMBER';
}
