package ai.cerbur.crag.console.auth.dto;

import java.time.Instant;

/**
 * Console Auth 响应（plan_21/21.6）。
 *
 * <p>register/login/refresh 返回 Access JWT 与过期时间。完整 Refresh Token 不出现在此对象，仅通过 HttpOnly Cookie 下发。
 * register 额外携带 默认 Tenant；login 时为 null，客户端通过 Tenant 列表恢复上下文。
 *
 * @param accessToken Access JWT
 * @param accessExpiresAt Access JWT 过期时间（RFC 3339 UTC）
 * @param user 用户安全投影
 * @param defaultTenant 默认 Tenant（仅 register 非空）
 */
public record AuthResponse(
    String accessToken,
    Instant accessExpiresAt,
    UserResponse user,
    TenantSummaryResponse defaultTenant) {}
