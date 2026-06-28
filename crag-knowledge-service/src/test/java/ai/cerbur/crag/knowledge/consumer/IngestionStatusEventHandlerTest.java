package ai.cerbur.crag.knowledge.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.cerbur.crag.event.api.EventEnvelope;
import ai.cerbur.crag.event.api.EventHandlerResult;
import ai.cerbur.crag.knowledge.core.ingestion.IngestionApplyResult;
import ai.cerbur.crag.knowledge.core.ingestion.IngestionApplyService;
import ai.cerbur.crag.knowledge.core.ingestion.IngestionStatusEvent;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * IngestionStatusEventHandler 单元测试（plan_21/21.3）。
 *
 * <p>验证 handler outcome 映射：非目标事件 ACK；payload 未知版本 DLQ；非法 payload DLQ；envelope/resourceId 与 payload
 * 不一致 DLQ；apply APPLIED/ACKNOWLEDGED → success；RETRYABLE → retryable；REJECTED → DLQ。 双层安全限长在
 * payload 解析与 toEvent 转换时生效。
 */
@DisplayName("IngestionStatusEventHandler")
class IngestionStatusEventHandlerTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  private IngestionStatusEventHandler handler(IngestionApplyService applyService) {
    return new IngestionStatusEventHandler(
        objectMapper,
        applyService,
        "crag:event:ingestion",
        "knowledge-ingestion",
        "knowledge-ingestion-1");
  }

  private IngestionApplyService applyReturning(IngestionApplyResult result) {
    IngestionApplyService service = mock(IngestionApplyService.class);
    when(service.apply(any(IngestionStatusEvent.class))).thenReturn(result);
    return service;
  }

  private EventEnvelope ingestion(
      long eventId,
      String eventType,
      long resourceId,
      long operationVersion,
      int payloadVersion,
      String payload) {
    return new EventEnvelope(
        eventId,
        eventType,
        "rag-service",
        "DOCUMENT",
        resourceId,
        operationVersion,
        payloadVersion,
        Instant.now(),
        "trace-" + eventId,
        payload);
  }

  private String validPayload(
      long tenantId, long kbId, long docId, long opVersion, String targetStatus) {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("tenantId", tenantId)
        .put("knowledgeBaseId", kbId)
        .put("docId", docId)
        .put("operationVersion", opVersion)
        .put("targetStatus", targetStatus);
    return node.toString();
  }

  @Test
  @DisplayName("非 INGESTION_* 事件 → success（ACK），不调用 apply")
  void nonIngestionEventAcked() {
    IngestionApplyService service = applyReturning(IngestionApplyResult.applied("x"));
    EventEnvelope docUploaded =
        new EventEnvelope(
            1L,
            "DOC_UPLOADED",
            "knowledge-service",
            "DOCUMENT",
            1L,
            1L,
            1,
            Instant.now(),
            "t",
            "{}");

    EventHandlerResult result = handler(service).handle(docUploaded);

    assertThat(result.status()).isEqualTo(EventHandlerResult.Status.SUCCESS);
    verify(service, never()).apply(any());
  }

  @Test
  @DisplayName("合法 payload v1 + apply APPLIED → success")
  void validPayloadAppliedSuccess() {
    IngestionApplyService service = applyReturning(IngestionApplyResult.applied("applied"));
    EventEnvelope env =
        ingestion(
            10L,
            "INGESTION_PROCESSING",
            100L,
            1L,
            1,
            validPayload(7L, 10L, 100L, 1L, "PROCESSING"));

    EventHandlerResult result = handler(service).handle(env);

    assertThat(result.status()).isEqualTo(EventHandlerResult.Status.SUCCESS);
    verify(service, times(1)).apply(any());
  }

  @Test
  @DisplayName("apply ACKNOWLEDGED（重复/旧版本）→ success")
  void applyAcknowledgedSuccess() {
    IngestionApplyService service = applyReturning(IngestionApplyResult.acknowledged("dup"));
    EventEnvelope env =
        ingestion(11L, "INGESTION_READY", 101L, 1L, 1, validPayload(7L, 10L, 101L, 1L, "READY"));

    EventHandlerResult result = handler(service).handle(env);

    assertThat(result.status()).isEqualTo(EventHandlerResult.Status.SUCCESS);
  }

  @Test
  @DisplayName("apply RETRYABLE → retryableFailure（留 Pending）")
  void applyRetryableRetry() {
    IngestionApplyService service = applyReturning(IngestionApplyResult.retryable("transient"));
    EventEnvelope env =
        ingestion(
            12L,
            "INGESTION_PROCESSING",
            102L,
            1L,
            1,
            validPayload(7L, 10L, 102L, 1L, "PROCESSING"));

    EventHandlerResult result = handler(service).handle(env);

    assertThat(result.status()).isEqualTo(EventHandlerResult.Status.RETRYABLE_FAILURE);
  }

  @Test
  @DisplayName("apply REJECTED → nonRetryableFailure（DLQ）")
  void applyRejectedDeadLetter() {
    IngestionApplyService service = applyReturning(IngestionApplyResult.rejected("kb mismatch"));
    EventEnvelope env =
        ingestion(
            13L,
            "INGESTION_PROCESSING",
            103L,
            1L,
            1,
            validPayload(7L, 10L, 103L, 1L, "PROCESSING"));

    EventHandlerResult result = handler(service).handle(env);

    assertThat(result.status()).isEqualTo(EventHandlerResult.Status.NON_RETRYABLE_FAILURE);
  }

  @Test
  @DisplayName("payload 版本未知（v2）→ nonRetryableFailure，不调用 apply")
  void unknownPayloadVersionDeadLetter() {
    IngestionApplyService service = applyReturning(IngestionApplyResult.applied("x"));
    EventEnvelope env =
        ingestion(
            14L,
            "INGESTION_PROCESSING",
            104L,
            1L,
            2,
            validPayload(7L, 10L, 104L, 1L, "PROCESSING"));

    EventHandlerResult result = handler(service).handle(env);

    assertThat(result.status()).isEqualTo(EventHandlerResult.Status.NON_RETRYABLE_FAILURE);
    verify(service, never()).apply(any());
  }

  @Test
  @DisplayName("非法 payload（缺字段）→ nonRetryableFailure，不调用 apply")
  void invalidPayloadDeadLetter() {
    IngestionApplyService service = applyReturning(IngestionApplyResult.applied("x"));
    EventEnvelope env = ingestion(15L, "INGESTION_PROCESSING", 105L, 1L, 1, "{\"tenantId\":7}");

    EventHandlerResult result = handler(service).handle(env);

    assertThat(result.status()).isEqualTo(EventHandlerResult.Status.NON_RETRYABLE_FAILURE);
    verify(service, never()).apply(any());
  }

  @Test
  @DisplayName("envelope resourceId 与 payload docId 不一致 → nonRetryableFailure（DLQ）")
  void envelopeResourceIdMismatchDeadLetter() {
    IngestionApplyService service = applyReturning(IngestionApplyResult.applied("x"));
    // envelope resourceId=200，payload docId=100
    EventEnvelope env =
        ingestion(
            16L,
            "INGESTION_PROCESSING",
            200L,
            1L,
            1,
            validPayload(7L, 10L, 100L, 1L, "PROCESSING"));

    EventHandlerResult result = handler(service).handle(env);

    assertThat(result.status()).isEqualTo(EventHandlerResult.Status.NON_RETRYABLE_FAILURE);
    verify(service, never()).apply(any());
  }

  @Test
  @DisplayName("envelope operationVersion 与 payload 不一致 → nonRetryableFailure（DLQ）")
  void envelopeOperationVersionMismatchDeadLetter() {
    IngestionApplyService service = applyReturning(IngestionApplyResult.applied("x"));
    EventEnvelope env =
        ingestion(
            17L,
            "INGESTION_PROCESSING",
            106L,
            99L, // envelope operationVersion
            1,
            validPayload(7L, 10L, 106L, 1L, "PROCESSING"));

    EventHandlerResult result = handler(service).handle(env);

    assertThat(result.status()).isEqualTo(EventHandlerResult.Status.NON_RETRYABLE_FAILURE);
    verify(service, never()).apply(any());
  }

  @Test
  @DisplayName("FAILED payload failureMessage 超过列长度时双层截断到列上限，仍 success")
  void failureMessageTruncated() {
    IngestionApplyService service = applyReturning(IngestionApplyResult.applied("applied"));
    ObjectNode node = objectMapper.createObjectNode();
    node.put("tenantId", 7L)
        .put("knowledgeBaseId", 10L)
        .put("docId", 107L)
        .put("operationVersion", 1L)
        .put("targetStatus", "FAILED")
        .put("failureCategory", "INDEX_TRANSIENT_FAILURE")
        .put("failureMessage", "x".repeat(2000));
    EventEnvelope env = ingestion(18L, "INGESTION_FAILED", 107L, 1L, 1, node.toString());

    EventHandlerResult result = handler(service).handle(env);

    assertThat(result.status()).isEqualTo(EventHandlerResult.Status.SUCCESS);
    // 验证传给 apply 的事件 message 已截断到列上限。
    org.mockito.ArgumentCaptor<IngestionStatusEvent> captor =
        org.mockito.ArgumentCaptor.forClass(IngestionStatusEvent.class);
    verify(service).apply(captor.capture());
    assertThat(captor.getValue().failureMessage()).hasSize(512);
  }

  @Test
  @DisplayName("handler 元数据：eventTypes 为三类 INGESTION_*；stream/group/consumer 来自配置")
  void handlerMetadata() {
    IngestionStatusEventHandler h = handler(mock(IngestionApplyService.class));
    assertThat(h.eventTypes())
        .containsExactlyInAnyOrder("INGESTION_PROCESSING", "INGESTION_READY", "INGESTION_FAILED");
    assertThat(h.streamKey()).isEqualTo("crag:event:ingestion");
    assertThat(h.groupName()).isEqualTo("knowledge-ingestion");
    assertThat(h.consumerName()).isEqualTo("knowledge-ingestion-1");
  }
}
