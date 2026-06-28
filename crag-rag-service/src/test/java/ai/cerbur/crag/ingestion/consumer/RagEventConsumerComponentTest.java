package ai.cerbur.crag.ingestion.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.cerbur.crag.event.api.EventEnvelope;
import ai.cerbur.crag.event.api.EventHandlerResult;
import ai.cerbur.crag.event.api.EventHandlerResult.Status;
import ai.cerbur.crag.ingestion.head.IngestionHeadService;
import ai.cerbur.crag.ingestion.head.StaleIndexCleaner;
import ai.cerbur.crag.ingestion.job.IngestionJobService;
import ai.cerbur.crag.ingestion.job.IngestionOrchestrator;
import ai.cerbur.crag.storage.IngestionJobDao;
import ai.cerbur.crag.storage.entity.IngestionJob;
import ai.cerbur.crag.storage.entity.IngestionJobStatus;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * DocUploadedEventHandler 组件测试（Plan 19）：验证 outcome 映射、双层幂等建 Job、非法 payload 安全失败与瞬时异常重试.
 *
 * <p>H2 下以真实 IngestionJobService 验证幂等建 Job；真实 Redis Streams 的 Pending reclaim / DLQ 行为由 Docker HTTP
 * 回归证明 （plan_19.7）.
 */
@SpringBootTest(classes = RagEventConsumerTestConfig.class)
@DisplayName("DocUploadedEventHandler 消费 outcome 与幂等")
class RagEventConsumerComponentTest {

  @Autowired private IngestionJobService ingestionJobService;
  @Autowired private IngestionHeadService ingestionHeadService;
  @Autowired private IngestionJobDao ingestionJobDao;
  @Autowired private ObjectMapper objectMapper;

  private DocUploadedEventHandler handler() {
    // 编排（读取/校验/切分/写入）在 IngestionOrchestratorTest 单独验证；此处用 no-op mock 聚焦 outcome 映射。
    return new DocUploadedEventHandler(
        objectMapper,
        ingestionJobService,
        mock(IngestionOrchestrator.class),
        ingestionHeadService,
        mock(StaleIndexCleaner.class),
        "crag:event:knowledge",
        "rag-ingestion",
        "rag-ingestion-1");
  }

  private EventEnvelope docUploaded(long docId, long opVer, String payloadJson) {
    return new EventEnvelope(
        docId,
        "DOC_UPLOADED",
        "knowledge-service",
        "DOCUMENT",
        docId,
        opVer,
        1,
        Instant.now(),
        "doc-" + docId,
        payloadJson);
  }

  private String validPayload(long kbId, long docId, long opVer) {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("tenantId", 7)
        .put("knowledgeBaseId", kbId)
        .put("docId", docId)
        .put("operationVersion", opVer);
    node.put("fileType", "TXT").put("sizeBytes", 42).put("sha256", "abc");
    return node.toString();
  }

  @Nested
  @DisplayName("合法 DOC_UPLOADED")
  class ValidDocUploaded {

    @Test
    @DisplayName("首次事件 → success 并创建唯一 PENDING Job")
    void firstEventCreatesJob() {
      EventHandlerResult result =
          handler().handle(docUploaded(5001L, 1L, validPayload(200L, 5001L, 1L)));

      assertThat(result.status()).isEqualTo(Status.SUCCESS);
      Optional<IngestionJob> job = ingestionJobDao.findByDocIdAndOperationVersion(5001L, 1L);
      assertThat(job).isPresent();
      assertThat(job.get().getStatus()).isEqualTo(IngestionJobStatus.PENDING);
      assertThat(job.get().getKnowledgeBaseId()).isEqualTo(200L);
    }

    @Test
    @DisplayName("重复事件 → success 且不创建第二个 Job")
    void duplicateEventDoesNotCreateSecondJob() {
      DocUploadedEventHandler h = handler();

      h.handle(docUploaded(5002L, 1L, validPayload(201L, 5002L, 1L)));
      EventHandlerResult second = h.handle(docUploaded(5002L, 1L, validPayload(201L, 5002L, 1L)));

      assertThat(second.status()).isEqualTo(Status.SUCCESS);
      long count =
          ingestionJobDao.countByKnowledgeBaseIdAndStatus(201L, IngestionJobStatus.PENDING);
      assertThat(count).isEqualTo(1);
    }
  }

  @Test
  @DisplayName("非 DOC_UPLOADED 事件 → success（ACK），不建 Job")
  void nonDocUploadedEventAckedWithoutJob() {
    EventEnvelope other =
        new EventEnvelope(
            9991L,
            "INGESTION_READY",
            "rag-service",
            "INGESTION_JOB",
            9991L,
            1L,
            1,
            Instant.now(),
            "trace",
            "{}");

    EventHandlerResult result = handler().handle(other);

    assertThat(result.status()).isEqualTo(Status.SUCCESS);
    assertThat(ingestionJobDao.findByDocIdAndOperationVersion(9991L, 1L)).isEmpty();
  }

  @Test
  @DisplayName("非法 payload → nonRetryableFailure（安全 DLQ）")
  void invalidPayloadDeadLettered() {
    EventEnvelope malformed =
        new EventEnvelope(
            5003L,
            "DOC_UPLOADED",
            "knowledge-service",
            "DOCUMENT",
            5003L,
            1L,
            1,
            Instant.now(),
            "trace",
            "{\"tenantId\":7}");

    EventHandlerResult result = handler().handle(malformed);

    assertThat(result.status()).isEqualTo(Status.NON_RETRYABLE_FAILURE);
    assertThat(ingestionJobDao.findByDocIdAndOperationVersion(5003L, 1L)).isEmpty();
  }

  @Test
  @DisplayName("Job 解析瞬时异常 → retryableFailure（留 Pending 等 reclaim）")
  void resolveTransientFailureIsRetryable() {
    IngestionJobService failingService = mock(IngestionJobService.class);
    when(failingService.resolve(
            anyLong(), anyLong(), eq(5004L), anyLong(), anyString(), anyLong(), anyString()))
        .thenThrow(new RuntimeException("db unavailable"));
    DocUploadedEventHandler h =
        new DocUploadedEventHandler(
            objectMapper,
            failingService,
            mock(IngestionOrchestrator.class),
            ingestionHeadService,
            mock(StaleIndexCleaner.class),
            "s",
            "g",
            "c");

    EventHandlerResult result = h.handle(docUploaded(5004L, 1L, validPayload(202L, 5004L, 1L)));

    assertThat(result.status()).isEqualTo(Status.RETRYABLE_FAILURE);
  }

  @Test
  @DisplayName("eventTypes 仅含 DOC_UPLOADED；stream/group/consumer 来自配置")
  void handlerMetadata() {
    DocUploadedEventHandler h = handler();

    assertThat(h.eventTypes()).containsExactly("DOC_UPLOADED");
    assertThat(h.streamKey()).isEqualTo("crag:event:knowledge");
    assertThat(h.groupName()).isEqualTo("rag-ingestion");
    assertThat(h.consumerName()).isEqualTo("rag-ingestion-1");
  }
}
