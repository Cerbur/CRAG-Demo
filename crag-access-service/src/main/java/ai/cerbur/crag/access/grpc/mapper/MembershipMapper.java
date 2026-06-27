package ai.cerbur.crag.access.grpc.mapper;

import ai.cerbur.crag.access.core.membership.AuthorizationDecision;
import ai.cerbur.crag.access.core.membership.MembershipResult;
import ai.cerbur.crag.access.core.membership.MembershipRole;
import ai.cerbur.crag.access.core.membership.TenantAction;
import ai.cerbur.crag.access.dao.entity.TenantMembershipEntity;
import ai.cerbur.crag.contracts.access.v1.Membership;
import ai.cerbur.crag.contracts.access.v1.MembershipStatus;

/** Membership 核心 result 与 proto 互转；core/proto 同名枚举用全限定名区分。 */
public final class MembershipMapper {

  private MembershipMapper() {}

  public static Membership toProto(MembershipResult result) {
    return Membership.newBuilder()
        .setMembershipId(Long.toString(result.membershipId()))
        .setTenantId(Long.toString(result.tenantId()))
        .setUserId(Long.toString(result.userId()))
        .setRole(toProtoRole(result.role()))
        .setStatus(toProtoStatus(result.status()))
        .setVersion(result.version())
        .build();
  }

  public static ai.cerbur.crag.contracts.access.v1.AuthorizationDecision toProto(
      AuthorizationDecision decision) {
    return ai.cerbur.crag.contracts.access.v1.AuthorizationDecision.newBuilder()
        .setAllowed(decision.allowed())
        .setAction(toProtoAction(decision.action()))
        .setReason(decision.reason())
        .build();
  }

  public static ai.cerbur.crag.contracts.access.v1.MembershipRole toProtoRole(MembershipRole role) {
    return role == MembershipRole.OWNER
        ? ai.cerbur.crag.contracts.access.v1.MembershipRole.MEMBERSHIP_ROLE_OWNER
        : ai.cerbur.crag.contracts.access.v1.MembershipRole.MEMBERSHIP_ROLE_MEMBER;
  }

  public static MembershipRole fromProtoRole(
      ai.cerbur.crag.contracts.access.v1.MembershipRole role) {
    return role == ai.cerbur.crag.contracts.access.v1.MembershipRole.MEMBERSHIP_ROLE_OWNER
        ? MembershipRole.OWNER
        : MembershipRole.MEMBER;
  }

  public static TenantAction fromProtoAction(
      ai.cerbur.crag.contracts.access.v1.TenantAction action) {
    return switch (action) {
      case TENANT_MANAGE_MEMBERS -> TenantAction.MANAGE_MEMBERS;
      case TENANT_CREATE_KNOWLEDGE_BASE -> TenantAction.CREATE_KNOWLEDGE_BASE;
      case TENANT_VIEW_KNOWLEDGE_BASE -> TenantAction.VIEW_KNOWLEDGE_BASE;
      case TENANT_UPLOAD_DOCUMENT -> TenantAction.UPLOAD_DOCUMENT;
      case TENANT_DELETE_OWN_DOCUMENT -> TenantAction.DELETE_OWN_DOCUMENT;
      case TENANT_DELETE_ANY_DOCUMENT -> TenantAction.DELETE_ANY_DOCUMENT;
      case TENANT_DELETE_KNOWLEDGE_BASE -> TenantAction.DELETE_KNOWLEDGE_BASE;
      case TENANT_MANAGE_API_KEY -> TenantAction.MANAGE_API_KEY;
      case TENANT_ACTION_UNSPECIFIED, UNRECOGNIZED ->
          throw new IllegalArgumentException("unspecified tenant action");
    };
  }

  private static MembershipStatus toProtoStatus(String status) {
    return TenantMembershipEntity.STATUS_ACTIVE.equals(status)
        ? MembershipStatus.MEMBERSHIP_STATUS_ACTIVE
        : MembershipStatus.MEMBERSHIP_STATUS_REMOVED;
  }

  private static ai.cerbur.crag.contracts.access.v1.TenantAction toProtoAction(
      TenantAction action) {
    return switch (action) {
      case MANAGE_MEMBERS -> ai.cerbur.crag.contracts.access.v1.TenantAction.TENANT_MANAGE_MEMBERS;
      case CREATE_KNOWLEDGE_BASE ->
          ai.cerbur.crag.contracts.access.v1.TenantAction.TENANT_CREATE_KNOWLEDGE_BASE;
      case VIEW_KNOWLEDGE_BASE ->
          ai.cerbur.crag.contracts.access.v1.TenantAction.TENANT_VIEW_KNOWLEDGE_BASE;
      case UPLOAD_DOCUMENT ->
          ai.cerbur.crag.contracts.access.v1.TenantAction.TENANT_UPLOAD_DOCUMENT;
      case DELETE_OWN_DOCUMENT ->
          ai.cerbur.crag.contracts.access.v1.TenantAction.TENANT_DELETE_OWN_DOCUMENT;
      case DELETE_ANY_DOCUMENT ->
          ai.cerbur.crag.contracts.access.v1.TenantAction.TENANT_DELETE_ANY_DOCUMENT;
      case DELETE_KNOWLEDGE_BASE ->
          ai.cerbur.crag.contracts.access.v1.TenantAction.TENANT_DELETE_KNOWLEDGE_BASE;
      case MANAGE_API_KEY -> ai.cerbur.crag.contracts.access.v1.TenantAction.TENANT_MANAGE_API_KEY;
    };
  }
}
