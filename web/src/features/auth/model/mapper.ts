/**
 * Pure mappers from auth DTOs (raw `result` payloads returned by the transport)
 * to the {@link AuthSession} domain type.
 *
 * The transport returns `result` as `unknown`. Each mapper validates the shape
 * defensively and throws a typed error when the contract is violated — never
 * trusting unchecked casts. Field-level validation errors (HTTP 400) are handled
 * by the ApiError pipeline, not here; this module only narrows well-formed
 * success payloads.
 *
 * Tenant recovery rule (docs/api/README.md §2):
 *  - register → `defaultTenant` is non-null; use it directly.
 *  - login/refresh → `defaultTenant` is null; the caller must additionally
 *    fetch `GET /api/v1/tenants` and pass the first tenant to
 *    {@link buildSessionFromLogin}.
 */
import type { AuthSession } from '@entities/session';
import type {
  AuthResponseDto,
  TenantListResponseDto,
  TenantSummaryDto,
  UserResponseDto,
} from './dto';

/** Thrown when a DTO does not match the OpenAPI contract. */
export class AuthDtoError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'AuthDtoError';
  }
}

function isObject(v: unknown): v is Record<string, unknown> {
  return typeof v === 'object' && v !== null;
}

function requireString(obj: Record<string, unknown>, key: string): string {
  const v = obj[key];
  if (typeof v !== 'string' || v.length === 0) {
    throw new AuthDtoError(`expected string field "${key}"`);
  }
  return v;
}

function asTenantSummary(v: unknown): TenantSummaryDto {
  if (!isObject(v)) throw new AuthDtoError('tenant summary is not an object');
  const role = v['role'];
  if (role !== 'OWNER' && role !== 'MEMBER') {
    throw new AuthDtoError('tenant role must be OWNER or MEMBER');
  }
  return {
    tenantId: requireString(v, 'tenantId'),
    name: requireString(v, 'name'),
    role,
  };
}

function asAuthResponse(v: unknown): AuthResponseDto {
  if (!isObject(v)) throw new AuthDtoError('auth response is not an object');
  const user = v['user'];
  if (!isObject(user)) throw new AuthDtoError('auth response.user is not an object');
  const defaultTenantRaw = v['defaultTenant'];
  return {
    accessToken: requireString(v, 'accessToken'),
    accessExpiresAt: requireString(v, 'accessExpiresAt'),
    user: {
      userId: requireString(user, 'userId'),
      nickname: requireString(user, 'nickname'),
    },
    defaultTenant:
      defaultTenantRaw === null || defaultTenantRaw === undefined
        ? null
        : asTenantSummary(defaultTenantRaw),
  };
}

function asUserResponse(v: unknown): UserResponseDto {
  if (!isObject(v)) throw new AuthDtoError('user response is not an object');
  return {
    userId: requireString(v, 'userId'),
    nickname: requireString(v, 'nickname'),
  };
}

function asTenantList(v: unknown): TenantListResponseDto {
  if (!isObject(v)) throw new AuthDtoError('tenant list is not an object');
  const itemsRaw = v['items'];
  if (!Array.isArray(itemsRaw)) throw new AuthDtoError('tenant list.items is not an array');
  const items = itemsRaw.map(asTenantSummary);
  const nextPageTokenRaw = v['nextPageToken'];
  return {
    items,
    nextPageToken: typeof nextPageTokenRaw === 'string' ? nextPageTokenRaw : '',
  };
}

/**
 * Build an AuthSession from a register success payload. The register response
 * always carries a non-null `defaultTenant`; if it is missing the contract is
 * violated and we throw.
 */
export function buildSessionFromRegister(result: unknown): {
  readonly session: AuthSession;
  readonly accessToken: string;
  readonly accessExpiresAt: string;
} {
  const dto = asAuthResponse(result);
  if (dto.defaultTenant === null) {
    throw new AuthDtoError('register response missing defaultTenant');
  }
  return {
    session: {
      userId: dto.user.userId,
      nickname: dto.user.nickname,
      tenantId: dto.defaultTenant.tenantId,
      role: dto.defaultTenant.role,
    },
    accessToken: dto.accessToken,
    accessExpiresAt: dto.accessExpiresAt,
  };
}

/**
 * Build an AuthSession from a login/refresh payload paired with the first
 * tenant from GET /api/v1/tenants. Per OpenAPI, login/refresh return
 * defaultTenant=null and the client recovers the working tenant context from
 * the tenants list (typically the OWNER default tenant).
 */
export function buildSessionFromLogin(
  authResult: unknown,
  tenantsResult: unknown,
): {
  readonly session: AuthSession;
  readonly accessToken: string;
  readonly accessExpiresAt: string;
} {
  const dto = asAuthResponse(authResult);
  const tenants = asTenantList(tenantsResult);
  const first = tenants.items[0];
  if (!first) {
    throw new AuthDtoError('tenant list is empty');
  }
  return {
    session: {
      userId: dto.user.userId,
      nickname: dto.user.nickname,
      tenantId: first.tenantId,
      role: first.role,
    },
    accessToken: dto.accessToken,
    accessExpiresAt: dto.accessExpiresAt,
  };
}

/**
 * Build an AuthSession from GET /auth/me + GET /tenants — used by the session
 * bootstrap on page load to restore a session from the HttpOnly refresh cookie.
 */
export function buildSessionFromMe(meResult: unknown, tenantsResult: unknown): AuthSession {
  const user = asUserResponse(meResult);
  const tenants = asTenantList(tenantsResult);
  const first = tenants.items[0];
  if (!first) {
    throw new AuthDtoError('tenant list is empty');
  }
  return {
    userId: user.userId,
    nickname: user.nickname,
    tenantId: first.tenantId,
    role: first.role,
  };
}
