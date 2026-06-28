package ai.cerbur.crag.access.core.identity;

/**
 * 用户安全投影（plan_21/21.2）。不包含密码、账号状态或登录标识，供 Console {@code /api/v1/auth/me}。
 *
 * @param userId 用户 ID
 * @param nickname 展示名
 */
public record UserProfileResult(long userId, String nickname) {}
