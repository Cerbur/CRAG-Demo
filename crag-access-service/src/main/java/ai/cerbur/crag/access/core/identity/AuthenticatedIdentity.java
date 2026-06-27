package ai.cerbur.crag.access.core.identity;

/**
 * 登录认证成功结果：携带永久身份与展示名，供后续签发 JWT/Refresh Session（plan_20/20.5）。
 *
 * @param userId 永久用户 ID
 * @param accountId 登录账号 ID
 * @param nickname 展示名
 */
public record AuthenticatedIdentity(long userId, long accountId, String nickname) {}
