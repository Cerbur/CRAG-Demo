package ai.cerbur.crag.access.core.session;

/**
 * 认证结果：身份摘要与 Token 材料。
 *
 * @param userId 永久用户 ID
 * @param nickname 展示名
 * @param tenantId 调用方默认 Tenant（注册为新创建的默认 Tenant；登录/刷新回填首个有效 Tenant）
 * @param tokens Access JWT 与 Refresh Token
 */
public record AuthenticationResult(long userId, String nickname, long tenantId, TokenPair tokens) {}
