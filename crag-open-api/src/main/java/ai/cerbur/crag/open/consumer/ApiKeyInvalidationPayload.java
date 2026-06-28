package ai.cerbur.crag.open.consumer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Open 侧解析 {@code API_KEY_INVALIDATED} 事件 payload（plan_21/21.10）。
 *
 * <p>镜像 Access 生产端 {@code ApiKeyInvalidatedPayload}：{@code resourceType / resourceId / tenantId /
 * knowledgeBaseId / action / resourceVersion}。只读取定位与版本，不读取任何完整 Key、HMAC 或秘密。
 *
 * @param resourceType {@code API_KEY}（单 Key 失效）或 {@code API_KEY_SCOPE}（Scope 终态阻塞）
 * @param resourceId API Key ID（{@code API_KEY}）或 KnowledgeBase ID（{@code API_KEY_SCOPE}）
 * @param tenantId 租户 ID
 * @param knowledgeBaseId 知识库 ID
 * @param action 失效动作展示值
 * @param resourceVersion 资源逻辑操作版本（Key 或 Scope 的版本水位）
 */
public record ApiKeyInvalidationPayload(
    String resourceType,
    long resourceId,
    long tenantId,
    long knowledgeBaseId,
    String action,
    long resourceVersion) {

  /** 当前支持的 payload 结构版本。 */
  public static final int SUPPORTED_PAYLOAD_VERSION = 1;

  /** 单 Key 失效资源类型。 */
  public static final String RESOURCE_API_KEY = "API_KEY";

  /** Scope 终态阻塞资源类型。 */
  public static final String RESOURCE_API_KEY_SCOPE = "API_KEY_SCOPE";

  /** 解析 payload JSON；非法结构抛 {@link InvalidApiKeyInvalidationPayloadException}。 */
  public static ApiKeyInvalidationPayload parse(String json, ObjectMapper objectMapper)
      throws InvalidApiKeyInvalidationPayloadException {
    try {
      JsonNode node = objectMapper.readTree(json);
      String resourceType = requireText(node, "resourceType");
      return new ApiKeyInvalidationPayload(
          resourceType,
          requireLong(node, "resourceId"),
          requireLong(node, "tenantId"),
          requireLong(node, "knowledgeBaseId"),
          requireText(node, "action"),
          requireLong(node, "resourceVersion"));
    } catch (InvalidApiKeyInvalidationPayloadException e) {
      throw e;
    } catch (Exception e) {
      throw new InvalidApiKeyInvalidationPayloadException("invalid API_KEY_INVALIDATED payload", e);
    }
  }

  private static String requireText(JsonNode node, String field)
      throws InvalidApiKeyInvalidationPayloadException {
    JsonNode child = node.get(field);
    if (child == null || child.isNull() || !child.isTextual()) {
      throw new InvalidApiKeyInvalidationPayloadException("missing or non-text field: " + field);
    }
    String value = child.asText();
    if (value == null || value.isBlank()) {
      throw new InvalidApiKeyInvalidationPayloadException("blank field: " + field);
    }
    return value;
  }

  private static long requireLong(JsonNode node, String field)
      throws InvalidApiKeyInvalidationPayloadException {
    JsonNode child = node.get(field);
    if (child == null || child.isNull() || !child.canConvertToLong()) {
      throw new InvalidApiKeyInvalidationPayloadException("missing or non-long field: " + field);
    }
    return child.asLong();
  }
}
