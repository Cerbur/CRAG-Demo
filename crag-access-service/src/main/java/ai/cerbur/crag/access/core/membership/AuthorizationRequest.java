package ai.cerbur.crag.access.core.membership;

/**
 * 实时权限判断请求。
 *
 * @param actorUserId 操作者用户 ID
 * @param tenantId 租户 ID
 * @param action 固定权限动作
 * @param resourceOwnerUserId 资源上传者用户 ID；为空表示不区分自有/他人资源
 */
public record AuthorizationRequest(
    long actorUserId, long tenantId, TenantAction action, Long resourceOwnerUserId) {}
