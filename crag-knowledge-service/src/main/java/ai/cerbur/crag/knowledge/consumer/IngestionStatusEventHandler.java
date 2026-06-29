package ai.cerbur.crag.knowledge.consumer;

import ai.cerbur.crag.event.api.EventEnvelope;
import ai.cerbur.crag.event.api.EventHandler;
import ai.cerbur.crag.event.api.EventHandlerResult;
import ai.cerbur.crag.knowledge.core.ingestion.IngestionApplyResult;
import ai.cerbur.crag.knowledge.core.ingestion.IngestionApplyService;
import ai.cerbur.crag.knowledge.core.ingestion.IngestionStatusEvent;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Knowledge 消费 {@code INGESTION_PROCESSING / INGESTION_READY / INGESTION_FAILED} 的 {@link
 * EventHandler} （plan_21/21.3）。
 *
 * <p>双层幂等：crag-event 的 {@code processed_event} 保证同一投递不重复处理；业务层状态机 + CAS 保证同版本首个终态获胜、 重复终态
 * ACK、矛盾终态不覆盖事实、旧 operationVersion 仅 ACK。本类只负责 payload 解析、版本/归属校验与 outcome 映射。
 *
 * <p>outcome 语义：
 *
 * <ul>
 *   <li>非 INGESTION_* 事件（如 Knowledge 自发布的 DOC_UPLOADED）→ {@code success}，ACK 保持消费组干净；
 *   <li>未知 payload version → {@code nonRetryableFailure}（安全 DLQ）；
 *   <li>非法 payload（缺字段、类型错误、状态与事件类型不一致）→ {@code nonRetryableFailure}（安全 DLQ）；
 *   <li>envelope resourceId 与 payload docId 不一致，或 envelope operationVersion 与 payload 不一致 → {@code
 *       nonRetryableFailure}（归属不一致，DLQ）；
 *   <li>apply {@link IngestionApplyResult.Decision#APPLIED} 或 {@link
 *       IngestionApplyResult.Decision#ACKNOWLEDGED} → {@code success}；
 *   <li>apply {@link IngestionApplyResult.Decision#RETRYABLE} → {@code retryableFailure}（留 Pending
 *       等 reclaim）；
 *   <li>apply {@link IngestionApplyResult.Decision#REJECTED}（Tenant/KB/doc 不一致）→ {@code
 *       nonRetryableFailure}（DLQ）。
 * </ul>
 *
 * <p>failureMessage 双层安全限长：解析层保守上限 {@link IngestionStatusPayload#FAILURE_MESSAGE_PARSE_MAX}， 写库前列上限
 * {@link IngestionStatusPayload#FAILURE_MESSAGE_COLUMN_MAX}，列长度由 schema 限定。
 *
 * <p>真实 Redis Streams 的 Pending reclaim / DLQ 与 processed_event 幂等门由 crag-event 的
 * RedisStreamEventConsumer 在 启用 consumer 的环境执行。本 handler 在所有 Profile 注册；消费调度由 {@code
 * crag.event.consumer.enabled}（默认 false，生产 Compose 启用）驱动，轻量上下文不启动消费。
 */
@Component
public class IngestionStatusEventHandler implements EventHandler {

  private static final Logger log = LoggerFactory.getLogger(IngestionStatusEventHandler.class);

  private final ObjectMapper objectMapper;
  private final IngestionApplyService applyService;
  private final String streamKey;
  private final String groupName;
  private final String consumerName;

  public IngestionStatusEventHandler(
      ObjectMapper objectMapper,
      IngestionApplyService applyService,
      @Value("${crag.event.ingestion-stream-key:crag:event:ingestion}") String streamKey,
      @Value("${crag.event.knowledge-ingestion-group:knowledge-ingestion}") String groupName,
      @Value("${crag.event.knowledge-ingestion-consumer:knowledge-ingestion-1}")
          String consumerName) {
    this.objectMapper = objectMapper;
    this.applyService = applyService;
    this.streamKey = streamKey;
    this.groupName = groupName;
    this.consumerName = consumerName;
  }

  @Override
  public String consumerName() {
    return consumerName;
  }

  @Override
  public String streamKey() {
    return streamKey;
  }

  @Override
  public String groupName() {
    return groupName;
  }

  @Override
  public Set<String> eventTypes() {
    return IngestionStatusPayload.ACCEPTED_EVENT_TYPES;
  }

  @Override
  public EventHandlerResult handle(EventEnvelope envelope) {
    if (!eventTypes().contains(envelope.eventType())) {
      return EventHandlerResult.success();
    }
    if (envelope.payloadVersion() != IngestionStatusPayload.SUPPORTED_PAYLOAD_VERSION) {
      log.warn(
          "INGESTION_* payload version unsupported, dead-lettering — docId(resourceId)={} version={}",
          envelope.resourceIdAsString(),
          envelope.payloadVersion());
      return EventHandlerResult.nonRetryableFailure("unsupported INGESTION_* payload version");
    }

    IngestionStatusPayload payload;
    try {
      payload =
          IngestionStatusPayload.parse(envelope.eventType(), envelope.payload(), objectMapper);
    } catch (InvalidIngestionStatusPayloadException e) {
      log.warn(
          "INGESTION_* payload invalid, dead-lettering — docId(resourceId)={} reason={}",
          envelope.resourceIdAsString(),
          e.getMessage());
      return EventHandlerResult.nonRetryableFailure("invalid INGESTION_* payload");
    }

    if (envelope.resourceId() != payload.docId()
        || envelope.operationVersion() != payload.operationVersion()) {
      log.warn(
          "INGESTION_* envelope/payload identity mismatch, dead-lettering — "
              + "resourceId={} payloadDoc={} envelopeOp={} payloadOp={}",
          envelope.resourceIdAsString(),
          payload.docId(),
          envelope.operationVersionAsString(),
          payload.operationVersion());
      return EventHandlerResult.nonRetryableFailure(
          "envelope resourceId/operationVersion does not match payload");
    }

    IngestionStatusEvent event = payload.toEvent();
    IngestionApplyResult result;
    try {
      result = applyService.apply(event);
    } catch (RuntimeException e) {
      log.warn(
          "INGESTION_* apply threw, will retry — docId={} op={} reason={}",
          payload.docId(),
          payload.operationVersion(),
          e.getMessage());
      return EventHandlerResult.retryableFailure("INGESTION_* apply failed transiently");
    }

    return switch (result.decision()) {
      case APPLIED -> {
        log.info(
            "INGESTION_* projection applied — docId={} op={} status={}",
            payload.docId(),
            payload.operationVersion(),
            event.targetStatus());
        yield EventHandlerResult.success();
      }
      case ACKNOWLEDGED -> {
        log.info(
            "INGESTION_* acknowledged without apply — docId={} op={} reason={}",
            payload.docId(),
            payload.operationVersion(),
            result.reason());
        yield EventHandlerResult.success();
      }
      case RETRYABLE -> EventHandlerResult.retryableFailure("INGESTION_* apply retryable");
      case REJECTED ->
          EventHandlerResult.nonRetryableFailure("INGESTION_* rejected: " + result.reason());
    };
  }
}
