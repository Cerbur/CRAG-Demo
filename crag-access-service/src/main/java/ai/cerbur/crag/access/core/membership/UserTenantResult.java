package ai.cerbur.crag.access.core.membership;

/**
 * 用户所属 Tenant 投影（plan_21/21.2），包含该用户在 Tenant 中的角色，供 Console 恢复工作上下文。
 *
 * @param tenantId 租户 ID
 * @param name 租户展示名
 * @param role 用户在该 Tenant 的角色
 */
public record UserTenantResult(long tenantId, String name, MembershipRole role) {}
