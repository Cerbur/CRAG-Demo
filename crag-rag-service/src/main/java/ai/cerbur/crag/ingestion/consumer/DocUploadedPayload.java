package ai.cerbur.crag.ingestion.consumer;

import java.util.Objects;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * RAG 侧解析的 {@code DOC_UPLOADED} 事件 payload（Plan 19）.
 *
 * <p>字段与 Knowledge 生产者 {@code DocumentUploadedPayload} 对齐，只携带 RAG 摄取所需的安全字段。解析失败抛出 {@link
 * InvalidDocUploadedPayloadException}，由 consumer 映射为安全失败路径；禁止在异常消息中透传文件内容、storage key 或路径——payload
 * 本身不含这些字段.
 *
 * @param tenantId 租户 ID
 * @param knowledgeBaseId 知识库 ID
 * @param docId 文档 ID
 * @param operationVersion 文档逻辑操作版本
 * @param fileType 文件类型展示值（TXT / MARKDOWN）
 * @param sizeBytes 文件字节数
 * @param sha256 文件 sha256（十六进制小写）
 */
public record DocUploadedPayload(
    long tenantId,
    long knowledgeBaseId,
    long docId,
    long operationVersion,
    String fileType,
    long sizeBytes,
    String sha256) {

  /** RAG 支持的文件类型展示值. */
  public static final Set<String> SUPPORTED_FILE_TYPES = Set.of("TXT", "MARKDOWN");

  /**
   * 从 JSON 字符串解析并校验 payload.
   *
   * @param json DOC_UPLOADED 事件 payload JSON
   * @param objectMapper Jackson 解析器
   * @return 校验通过的 payload
   * @throws InvalidDocUploadedPayloadException 字段缺失、类型错误或值非法
   */
  public static DocUploadedPayload parse(String json, ObjectMapper objectMapper) {
    Objects.requireNonNull(json, "json");
    Objects.requireNonNull(objectMapper, "objectMapper");
    JsonNode node;
    try {
      node = objectMapper.readTree(json);
    } catch (RuntimeException e) {
      throw new InvalidDocUploadedPayloadException("payload is not valid JSON", e);
    }
    if (node == null || !node.isObject()) {
      throw new InvalidDocUploadedPayloadException("payload must be a JSON object");
    }
    long tenantId = requireLong(node, "tenantId");
    long knowledgeBaseId = requireLong(node, "knowledgeBaseId");
    long docId = requireLong(node, "docId");
    long operationVersion = requireLong(node, "operationVersion");
    String fileType = requireSupportedFileType(node);
    long sizeBytes = requirePositiveLong(node, "sizeBytes");
    String sha256 = requireNonBlank(node, "sha256");
    return new DocUploadedPayload(
        tenantId, knowledgeBaseId, docId, operationVersion, fileType, sizeBytes, sha256);
  }

  private static long requireLong(JsonNode node, String field) {
    JsonNode child = node.get(field);
    if (child == null || !child.isNumber()) {
      throw new InvalidDocUploadedPayloadException("field '" + field + "' missing or not a number");
    }
    return child.longValue();
  }

  private static long requirePositiveLong(JsonNode node, String field) {
    long value = requireLong(node, field);
    if (value <= 0) {
      throw new InvalidDocUploadedPayloadException("field '" + field + "' must be positive");
    }
    return value;
  }

  private static String requireNonBlank(JsonNode node, String field) {
    JsonNode child = node.get(field);
    if (child == null || !child.isTextual() || child.asString().isBlank()) {
      throw new InvalidDocUploadedPayloadException("field '" + field + "' missing or blank");
    }
    return child.asString();
  }

  private static String requireSupportedFileType(JsonNode node) {
    String fileType = requireNonBlank(node, "fileType");
    if (!SUPPORTED_FILE_TYPES.contains(fileType)) {
      throw new InvalidDocUploadedPayloadException("unsupported fileType: " + fileType);
    }
    return fileType;
  }
}
