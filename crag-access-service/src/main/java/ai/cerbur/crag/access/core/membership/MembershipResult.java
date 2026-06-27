package ai.cerbur.crag.access.core.membership;

import ai.cerbur.crag.access.dao.entity.TenantMembershipEntity;

/**
 * Membership 安全投影，不包含密码、账号状态或登录标识。
 *
 * @param membershipId 成员关系 ID
 * @param tenantId 租户 ID
 * @param userId 用户 ID
 * @param role 角色
 * @param status 状态
 * @param version 版本
 */
public record MembershipResult(
    long membershipId,
    long tenantId,
    long userId,
    MembershipRole role,
    String status,
    long version) {

  /** 从持久化实体投影。 */
  public static MembershipResult from(TenantMembershipEntity entity) {
    return new MembershipResult(
        entity.getMembershipId(),
        entity.getTenantId(),
        entity.getUserId(),
        MembershipRole.fromEntity(entity.getRole()),
        entity.getStatus(),
        entity.getVersion());
  }
}
