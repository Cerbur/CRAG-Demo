package ai.cerbur.crag.knowledge.producer;

import java.util.Objects;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Knowledge 发布的 {@code KNOWLEDGE_BASE_CREATED} 事件 payload（plan_21/21.3）。
 *
 * <p>字段集与 {@code crag-access-service} 的 {@code KnowledgeBaseCreatedPayload} 严格一致：{@code tenantId}、
 * {@code knowledgeBaseId}、{@code ownerUserId}。只携带 Access 建立 Scope 投影所需的安全字段，不含 KnowledgeBase 业务属性。
 * payload version 当前固定为 1。
 *
 * @param tenantId 租户 ID
 * @param knowledgeBaseId 知识库 ID
 * @param ownerUserId 创建者用户 ID
 */
public record KnowledgeBaseCreatedPayload(long tenantId, long knowledgeBaseId, long ownerUserId) {

  /** payload 版本，与 {@link KnowledgeEventTypes#PAYLOAD_VERSION} 一致。 */
  public static final int PAYLOAD_VERSION = KnowledgeEventTypes.PAYLOAD_VERSION;

  /** 序列化为 JSON；字段顺序固定便于测试断言。 */
  public String toJson(ObjectMapper objectMapper) {
    Objects.requireNonNull(objectMapper, "objectMapper");
    ObjectNode node = objectMapper.createObjectNode();
    node.put("tenantId", tenantId)
        .put("knowledgeBaseId", knowledgeBaseId)
        .put("ownerUserId", ownerUserId);
    return node.toString();
  }
}
