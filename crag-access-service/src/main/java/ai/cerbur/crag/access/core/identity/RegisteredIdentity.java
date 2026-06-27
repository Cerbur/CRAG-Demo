package ai.cerbur.crag.access.core.identity;

/**
 * 注册结果：原子创建的 User、USERNAME Account、默认 Tenant 与 OWNER Membership 的 ID。
 *
 * @param userId 永久用户 ID
 * @param accountId 登录账号 ID
 * @param tenantId 默认 Tenant ID
 * @param membershipId OWNER 成员关系 ID
 */
public record RegisteredIdentity(long userId, long accountId, long tenantId, long membershipId) {}
