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
 * @param nickname 成员展示名（plan_21/21.7：list 路径批量补齐，单成员命令亦可填充；永不为 null）
 */
public record MembershipResult(
    long membershipId,
    long tenantId,
    long userId,
    MembershipRole role,
    String status,
    long version,
    String nickname) {

  public MembershipResult {
    nickname = nickname == null ? "" : nickname;
  }

  /** 从持久化实体投影（nickname 缺省为空串）。 */
  public static MembershipResult from(TenantMembershipEntity entity) {
    return from(entity, "");
  }

  /** 从持久化实体投影并携带展示名（list 路径批量补齐 nickname）。 */
  public static MembershipResult from(TenantMembershipEntity entity, String nickname) {
    return new MembershipResult(
        entity.getMembershipId(),
        entity.getTenantId(),
        entity.getUserId(),
        MembershipRole.fromEntity(entity.getRole()),
        entity.getStatus(),
        entity.getVersion(),
        nickname);
  }
}
