package ai.cerbur.crag.access.core.membership;

/**
 * Tenant 固定权限矩阵（纯逻辑）。
 *
 * <p>OWNER 可执行全部动作；MEMBER 可创建/查看 KnowledgeBase、上传 Document、删除自己上传的 Document，但不能管理成员、删除他人
 * Document、删除 KnowledgeBase 或管理 API Key。删除自有资源需 {@code actorOwnsResource=true}，删除任意资源仅 OWNER。
 */
public final class TenantPermissionPolicy {

  private TenantPermissionPolicy() {}

  /** 按角色、动作与是否自有资源给出允许/拒绝决策。 */
  public static AuthorizationDecision decide(
      MembershipRole role, TenantAction action, boolean actorOwnsResource) {
    return evaluate(role, action, actorOwnsResource)
        ? AuthorizationDecision.allow(action)
        : AuthorizationDecision.deny(action);
  }

  private static boolean evaluate(
      MembershipRole role, TenantAction action, boolean actorOwnsResource) {
    return switch (action) {
      case MANAGE_MEMBERS, DELETE_KNOWLEDGE_BASE, MANAGE_API_KEY, DELETE_ANY_DOCUMENT ->
          role == MembershipRole.OWNER;
      case CREATE_KNOWLEDGE_BASE, VIEW_KNOWLEDGE_BASE, UPLOAD_DOCUMENT -> true;
      case DELETE_OWN_DOCUMENT -> role == MembershipRole.OWNER || actorOwnsResource;
    };
  }
}
