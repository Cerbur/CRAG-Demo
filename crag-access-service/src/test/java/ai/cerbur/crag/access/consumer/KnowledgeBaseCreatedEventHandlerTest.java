package ai.cerbur.crag.access.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.cerbur.crag.access.core.apikey.ApiKeyScopeResult;
import ai.cerbur.crag.access.core.apikey.ApiKeyService;
import ai.cerbur.crag.event.api.EventEnvelope;
import ai.cerbur.crag.event.api.EventHandlerResult;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * KnowledgeBaseCreatedEventHandler 单元测试（plan_21/21.2）。
 *
 * <p>验证 handler 只接受 KNOWLEDGE_BASE_CREATED payload v1、outcome 映射正确、重复事件不会重新建 Scope （由 EnsureScope
 * 业务幂等保证，processed_event 由 consumer 基础设施负责）， 以及非法 payload 进入安全 DLQ。consumer 层 processed_event 幂等门由
 * crag-event 的 RedisStreamEventConsumer 在真实环境执行，本测试聚焦 handler 业务行为。
 */
class KnowledgeBaseCreatedEventHandlerTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  private KnowledgeBaseCreatedEventHandler handler(ApiKeyService apiKeyService) {
    return new KnowledgeBaseCreatedEventHandler(
        objectMapper, apiKeyService, "crag:event:knowledge", "access-scope", "access-scope-1");
  }

  private EventEnvelope kbCreated(
      long eventId, long kbId, long tenantId, int payloadVersion, String payload) {
    return new EventEnvelope(
        eventId,
        KnowledgeBaseCreatedEventHandler.EVENT_TYPE,
        "knowledge-service",
        "KNOWLEDGE_BASE",
        kbId,
        1L,
        payloadVersion,
        Instant.now(),
        "trace-" + eventId,
        payload);
  }

  private String validPayload(long tenantId, long kbId) {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("tenantId", tenantId).put("knowledgeBaseId", kbId).put("ownerUserId", 0L);
    return node.toString();
  }

  @Test
  @DisplayName("合法 payload v1 → success 并调用 ensureScope")
  void validPayloadV1Succeeds() {
    ApiKeyService service = mock(ApiKeyService.class);
    when(service.ensureScope(anyLong(), anyLong(), anyLong()))
        .thenReturn(new ApiKeyScopeResult(2001L, 7L, "ACTIVE", 0L, 0L, 0L));

    EventHandlerResult result =
        handler(service).handle(kbCreated(1001L, 2001L, 7L, 1, validPayload(7L, 2001L)));

    assertThat(result.status()).isEqualTo(EventHandlerResult.Status.SUCCESS);
    // ensureScope 使用 payload 内的 ownerUserId=0 作为系统/事件驱动 actor；真实归属由 Knowledge 决定。
    verify(service, times(1)).ensureScope(0L, 7L, 2001L);
  }

  @Test
  @DisplayName("重复事件 → success（业务幂等由 ensureScope 保证，handler 不重复建 Scope）")
  void duplicateEventIsSuccessAndIdempotent() {
    ApiKeyService service = mock(ApiKeyService.class);
    when(service.ensureScope(anyLong(), anyLong(), anyLong()))
        .thenReturn(new ApiKeyScopeResult(2002L, 7L, "ACTIVE", 0L, 0L, 0L));
    KnowledgeBaseCreatedEventHandler h = handler(service);

    EventHandlerResult first = h.handle(kbCreated(1002L, 2002L, 7L, 1, validPayload(7L, 2002L)));
    EventHandlerResult second = h.handle(kbCreated(1002L, 2002L, 7L, 1, validPayload(7L, 2002L)));

    assertThat(first.status()).isEqualTo(EventHandlerResult.Status.SUCCESS);
    assertThat(second.status()).isEqualTo(EventHandlerResult.Status.SUCCESS);
    // handler 总是调用 ensureScope；幂等由业务层（scope 主键 + 状态）保证不创建重复 Scope。
    verify(service, times(2)).ensureScope(0L, 7L, 2002L);
  }

  @Test
  @DisplayName("非 KNOWLEDGE_BASE_CREATED 事件 → success（ACK），不调用 ensureScope")
  void nonKbCreatedEventAckedWithoutSideEffect() {
    ApiKeyService service = mock(ApiKeyService.class);
    EventEnvelope other =
        new EventEnvelope(
            9991L,
            "DOC_UPLOADED",
            "knowledge-service",
            "DOCUMENT",
            9991L,
            1L,
            1,
            Instant.now(),
            "trace",
            "{}");

    EventHandlerResult result = handler(service).handle(other);

    assertThat(result.status()).isEqualTo(EventHandlerResult.Status.SUCCESS);
    verify(service, never()).ensureScope(anyLong(), anyLong(), anyLong());
  }

  @Test
  @DisplayName("payload v2（未知版本）→ nonRetryableFailure（安全 DLQ）")
  void unknownPayloadVersionDeadLettered() {
    ApiKeyService service = mock(ApiKeyService.class);

    EventHandlerResult result =
        handler(service).handle(kbCreated(1003L, 2003L, 7L, 2, validPayload(7L, 2003L)));

    assertThat(result.status()).isEqualTo(EventHandlerResult.Status.NON_RETRYABLE_FAILURE);
    verify(service, never()).ensureScope(anyLong(), anyLong(), anyLong());
  }

  @Test
  @DisplayName("非法 payload → nonRetryableFailure，不泄漏字段值")
  void invalidPayloadDeadLettered() {
    ApiKeyService service = mock(ApiKeyService.class);

    EventHandlerResult result =
        handler(service).handle(kbCreated(1004L, 2004L, 7L, 1, "{\"tenantId\":7}"));

    assertThat(result.status()).isEqualTo(EventHandlerResult.Status.NON_RETRYABLE_FAILURE);
    verify(service, never()).ensureScope(anyLong(), anyLong(), anyLong());
  }

  @Test
  @DisplayName("ensureScope 瞬时异常 → retryableFailure（留 Pending 等 reclaim）")
  void transientFailureIsRetryable() {
    ApiKeyService service = mock(ApiKeyService.class);
    when(service.ensureScope(anyLong(), anyLong(), anyLong()))
        .thenThrow(new RuntimeException("db unavailable"));

    EventHandlerResult result =
        handler(service).handle(kbCreated(1005L, 2005L, 7L, 1, validPayload(7L, 2005L)));

    assertThat(result.status()).isEqualTo(EventHandlerResult.Status.RETRYABLE_FAILURE);
  }

  @Test
  @DisplayName("handler 元数据：eventTypes 仅 KNOWLEDGE_BASE_CREATED；stream/group/consumer 来自配置")
  void handlerMetadata() {
    KnowledgeBaseCreatedEventHandler h = handler(mock(ApiKeyService.class));

    assertThat(h.eventTypes()).containsExactly(KnowledgeBaseCreatedEventHandler.EVENT_TYPE);
    assertThat(h.streamKey()).isEqualTo("crag:event:knowledge");
    assertThat(h.groupName()).isEqualTo("access-scope");
    assertThat(h.consumerName()).isEqualTo("access-scope-1");
  }
}
