package ai.cerbur.crag.access.core.membership;

import ai.cerbur.crag.access.dao.entity.TenantMembershipEntity;

/** Membership 角色，与持久化字符串互转。 */
public enum MembershipRole {
  OWNER,
  MEMBER;

  /** 从持久化角色字符串解析。 */
  public static MembershipRole fromEntity(String role) {
    return switch (role) {
      case TenantMembershipEntity.ROLE_OWNER -> OWNER;
      case TenantMembershipEntity.ROLE_MEMBER -> MEMBER;
      default -> throw new IllegalArgumentException("unknown membership role: " + role);
    };
  }

  /** 转为持久化角色字符串。 */
  public String toEntity() {
    return this == OWNER ? TenantMembershipEntity.ROLE_OWNER : TenantMembershipEntity.ROLE_MEMBER;
  }
}
