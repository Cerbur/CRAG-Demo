package ai.cerbur.crag.access.core.membership;

/** Tenant 固定权限动作。{@code DELETE_OWN_DOCUMENT} 与 {@code DELETE_ANY_DOCUMENT} 通过调用方传入资源上传者区分。 */
public enum TenantAction {
  MANAGE_MEMBERS,
  CREATE_KNOWLEDGE_BASE,
  VIEW_KNOWLEDGE_BASE,
  UPLOAD_DOCUMENT,
  DELETE_OWN_DOCUMENT,
  DELETE_ANY_DOCUMENT,
  DELETE_KNOWLEDGE_BASE,
  MANAGE_API_KEY
}
