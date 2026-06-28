package ai.cerbur.crag.access.consumer;

import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Access 侧解析的 {@code KNOWLEDGE_BASE_CREATED} 事件 payload（plan_21/21.2）。
 *
 * <p>Knowledge 创建 KnowledgeBase 后在同一事务写本事件；Access 消费后通过 {@link
 * ai.cerbur.crag.access.core.apikey.ApiKeyService#ensureScope} 幂等补齐 Scope。payload 只携带 Access 建立授权
 * 投影所需的安全字段，不含 KnowledgeBase 业务属性。payload version 当前固定为 1；消费者拒绝未知版本以支持后续演进。
 *
 * @param tenantId 租户 ID（KnowledgeBase 归属租户）
 * @param knowledgeBaseId 知识库 ID
 * @param ownerUserId 创建者用户 ID（事件驱动场景作为 actorUserId 传入 ensureScope；为 0 表示系统补偿）
 */
public record KnowledgeBaseCreatedPayload(long tenantId, long knowledgeBaseId, long ownerUserId) {

  /** 当前支持的 payload 版本；未知版本进入安全 DLQ。 */
  public static final int SUPPORTED_PAYLOAD_VERSION = 1;

  /**
   * 从 JSON 字符串解析并校验 payload。
   *
   * @param json KNOWLEDGE_BASE_CREATED 事件 payload JSON
   * @param objectMapper Jackson 解析器
   * @return 校验通过的 payload
   * @throws InvalidKnowledgeBaseCreatedPayloadException 字段缺失、类型错误或值非法
   */
  public static KnowledgeBaseCreatedPayload parse(String json, ObjectMapper objectMapper) {
    Objects.requireNonNull(json, "json");
    Objects.requireNonNull(objectMapper, "objectMapper");
    JsonNode node;
    try {
      node = objectMapper.readTree(json);
    } catch (RuntimeException e) {
      throw new InvalidKnowledgeBaseCreatedPayloadException("payload is not valid JSON", e);
    }
    if (node == null || !node.isObject()) {
      throw new InvalidKnowledgeBaseCreatedPayloadException("payload must be a JSON object");
    }
    long tenantId = requireLong(node, "tenantId");
    long knowledgeBaseId = requireLong(node, "knowledgeBaseId");
    // ownerUserId 允许缺省为 0（事件驱动补偿场景），不要求 Knowledge 一定回填。
    long ownerUserId = optionalLong(node, "ownerUserId", 0L);
    return new KnowledgeBaseCreatedPayload(tenantId, knowledgeBaseId, ownerUserId);
  }

  private static long requireLong(JsonNode node, String field) {
    JsonNode child = node.get(field);
    if (child == null || !child.isNumber()) {
      throw new InvalidKnowledgeBaseCreatedPayloadException(
          "field '" + field + "' missing or not a number");
    }
    return child.longValue();
  }

  private static long optionalLong(JsonNode node, String field, long defaultValue) {
    JsonNode child = node.get(field);
    if (child == null || child.isNull()) {
      return defaultValue;
    }
    if (!child.isNumber()) {
      throw new InvalidKnowledgeBaseCreatedPayloadException(
          "field '" + field + "' is not a number");
    }
    return child.longValue();
  }
}
