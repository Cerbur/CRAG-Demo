package ai.cerbur.crag.access.producer;

/**
 * {@code API_KEY_INVALIDATED} 事件 payload。
 *
 * <p>仅携带 router4 批量失效缓存所需的 Key/Scope 定位与 action/version； <strong>禁止</strong>包含完整 Key、HMAC、Pepper
 * 或凭据。
 *
 * @param resourceType 资源类型（API_KEY / API_KEY_SCOPE）
 * @param resourceId 资源 ID（API Key ID 或 KnowledgeBase ID）
 * @param tenantId 租户 ID
 * @param knowledgeBaseId 知识库 ID
 * @param action 失效动作展示值
 * @param resourceVersion 资源逻辑操作版本
 */
public record ApiKeyInvalidatedPayload(
    String resourceType,
    long resourceId,
    long tenantId,
    long knowledgeBaseId,
    String action,
    long resourceVersion) {}
