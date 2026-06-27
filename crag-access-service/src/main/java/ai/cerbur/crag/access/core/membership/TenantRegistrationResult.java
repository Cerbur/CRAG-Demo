package ai.cerbur.crag.access.core.membership;

/**
 * 注册时原子创建的默认 Tenant 与 OWNER Membership 结果。
 *
 * @param tenantId 默认 Tenant ID
 * @param membershipId 注册者的 OWNER 成员关系 ID
 */
public record TenantRegistrationResult(long tenantId, long membershipId) {}
