package ai.cerbur.crag.knowledge.consumer;

import ai.cerbur.crag.knowledge.core.ingestion.IngestionStatus;
import ai.cerbur.crag.knowledge.core.ingestion.IngestionStatusEvent;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Knowledge 解析的 {@code INGESTION_*} 事件 payload（plan_21/21.3）。
 *
 * <p>RAG（plan_21/21.4）在 Job 状态推进时发布 {@code INGESTION_PROCESSING / INGESTION_READY /
 * INGESTION_FAILED}，Knowledge 消费后将状态投影回 Document。payload 只携带 Knowledge 写投影所需的安全字段；
 * 失败描述在解析时做双层安全限长，列长度上限由 {@link #FAILURE_MESSAGE_MAX} 控制。
 *
 * <p>payload version 当前固定为 1；消费者拒绝未知版本以支持后续演进。
 *
 * @param tenantId 租户 ID
 * @param knowledgeBaseId 知识库 ID
 * @param docId 文档 ID
 * @param operationVersion 本事件针对的 operationVersion
 * @param attempt RAG Job 当前 attempt；可空
 * @param jobId RAG Ingestion Job 本地 ID；可空
 * @param targetStatus 目标状态（PROCESSING/READY/FAILED）
 * @param failureCategory 失败分类；仅 FAILED 携带
 * @param failureMessage 安全限长后的失败描述；仅 FAILED 携带
 * @param startedAtEpochMillis RAG Job 起始时间（epoch 毫秒）；可空
 * @param completedAtEpochMillis RAG Job 完成时间（epoch 毫秒）；可空
 */
public record IngestionStatusPayload(
    long tenantId,
    long knowledgeBaseId,
    long docId,
    long operationVersion,
    Integer attempt,
    Long jobId,
    IngestionStatus targetStatus,
    String failureCategory,
    String failureMessage,
    Long startedAtEpochMillis,
    Long completedAtEpochMillis) {

  /** 当前支持的 payload 版本；未知版本进入安全 DLQ。 */
  public static final int SUPPORTED_PAYLOAD_VERSION = 1;

  /** 列存储 {@code failure_message} 长度上限（与 schema-knowledge.sql 一致）。 */
  public static final int FAILURE_MESSAGE_COLUMN_MAX = 512;

  /** 解析时的第一层保守上限（防止巨型 payload），第二层在写库前列截断。 */
  public static final int FAILURE_MESSAGE_PARSE_MAX = 1024;

  /** Knowledge 接受的事件类型前缀。 */
  public static final Set<String> ACCEPTED_EVENT_TYPES =
      Set.of("INGESTION_PROCESSING", "INGESTION_READY", "INGESTION_FAILED");

  /**
   * 从 JSON 字符串解析并校验 payload，同时将 {@code targetStatus} 从事件类型推导、failureMessage 安全限长。
   *
   * @param eventType envelope 的事件类型（INGESTION_PROCESSING/INGESTION_READY/INGESTION_FAILED）
   * @param json 事件 payload JSON
   * @param objectMapper Jackson 解析器
   * @return 校验通过的 payload（targetStatus 已从 eventType 推导并校验与 payload 字段一致）
   * @throws InvalidIngestionStatusPayloadException 字段缺失、类型错误或值非法
   */
  public static IngestionStatusPayload parse(
      String eventType, String json, ObjectMapper objectMapper) {
    Objects.requireNonNull(eventType, "eventType");
    Objects.requireNonNull(json, "json");
    Objects.requireNonNull(objectMapper, "objectMapper");
    JsonNode node;
    try {
      node = objectMapper.readTree(json);
    } catch (RuntimeException e) {
      throw new InvalidIngestionStatusPayloadException("payload is not valid JSON", e);
    }
    if (node == null || !node.isObject()) {
      throw new InvalidIngestionStatusPayloadException("payload must be a JSON object");
    }
    long tenantId = requireLong(node, "tenantId");
    long knowledgeBaseId = requireLong(node, "knowledgeBaseId");
    long docId = requireLong(node, "docId");
    long operationVersion = requireLong(node, "operationVersion");
    Integer attempt = optionalInt(node, "attempt");
    Long jobId = optionalLong(node, "jobId");
    Long startedAt = optionalLong(node, "startedAtEpochMillis");
    Long completedAt = optionalLong(node, "completedAtEpochMillis");

    IngestionStatus targetStatus = targetStatusFromEventType(eventType);
    // 若 payload 显式携带 targetStatus，则与事件类型推导结果必须一致，否则视为非法。
    String declaredStatus = optionalString(node, "targetStatus");
    if (declaredStatus != null) {
      IngestionStatus declared;
      try {
        declared = IngestionStatus.fromCode(declaredStatus);
      } catch (RuntimeException e) {
        throw new InvalidIngestionStatusPayloadException("invalid targetStatus: " + declaredStatus);
      }
      if (declared != targetStatus) {
        throw new InvalidIngestionStatusPayloadException(
            "targetStatus mismatch: event=" + eventType + " payload=" + declaredStatus);
      }
    }

    String failureCategory = null;
    String failureMessage = null;
    if (targetStatus == IngestionStatus.FAILED) {
      failureCategory = optionalString(node, "failureCategory");
      failureMessage = optionalString(node, "failureMessage");
      if (failureMessage != null) {
        failureMessage = truncate(failureMessage, FAILURE_MESSAGE_PARSE_MAX);
      }
    }

    return new IngestionStatusPayload(
        tenantId,
        knowledgeBaseId,
        docId,
        operationVersion,
        attempt,
        jobId,
        targetStatus,
        failureCategory,
        failureMessage,
        startedAt,
        completedAt);
  }

  /** 转换为 {@link IngestionStatusEvent}，并将 failureMessage 在第二层截断到列上限。 */
  public IngestionStatusEvent toEvent() {
    String safeMessage =
        failureMessage == null ? null : truncate(failureMessage, FAILURE_MESSAGE_COLUMN_MAX);
    return new IngestionStatusEvent(
        tenantId,
        knowledgeBaseId,
        docId,
        operationVersion,
        attempt,
        jobId,
        targetStatus,
        failureCategory,
        safeMessage,
        toLocalDateTime(startedAtEpochMillis),
        toLocalDateTime(completedAtEpochMillis));
  }

  private static IngestionStatus targetStatusFromEventType(String eventType) {
    return switch (eventType) {
      case "INGESTION_PROCESSING" -> IngestionStatus.PROCESSING;
      case "INGESTION_READY" -> IngestionStatus.READY;
      case "INGESTION_FAILED" -> IngestionStatus.FAILED;
      default ->
          throw new InvalidIngestionStatusPayloadException(
              "unsupported event type for ingestion status: " + eventType);
    };
  }

  private static long requireLong(JsonNode node, String field) {
    JsonNode child = node.get(field);
    if (child == null || !child.isNumber()) {
      throw new InvalidIngestionStatusPayloadException(
          "field '" + field + "' missing or not a number");
    }
    return child.longValue();
  }

  private static Long optionalLong(JsonNode node, String field) {
    JsonNode child = node.get(field);
    if (child == null || child.isNull()) {
      return null;
    }
    if (!child.isNumber()) {
      throw new InvalidIngestionStatusPayloadException("field '" + field + "' is not a number");
    }
    return child.longValue();
  }

  private static Integer optionalInt(JsonNode node, String field) {
    JsonNode child = node.get(field);
    if (child == null || child.isNull()) {
      return null;
    }
    if (!child.isIntegralNumber()) {
      throw new InvalidIngestionStatusPayloadException("field '" + field + "' is not an integer");
    }
    return child.intValue();
  }

  private static String optionalString(JsonNode node, String field) {
    JsonNode child = node.get(field);
    if (child == null || child.isNull()) {
      return null;
    }
    if (!child.isTextual()) {
      throw new InvalidIngestionStatusPayloadException("field '" + field + "' is not a string");
    }
    return child.asText();
  }

  private static String truncate(String value, int max) {
    return value.length() <= max ? value : value.substring(0, max);
  }

  private static LocalDateTime toLocalDateTime(Long epochMillis) {
    return epochMillis == null
        ? null
        : LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneOffset.UTC);
  }
}
