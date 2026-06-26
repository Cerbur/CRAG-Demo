package ai.cerbur.crag.ingestion.producer;

import ai.cerbur.crag.event.api.EventEnvelope;
import ai.cerbur.crag.event.jdbc.JdbcOutboxEventDao;
import ai.cerbur.crag.storage.entity.IngestionJob;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * RAG ingestion 状态事件 Outbox 写入器（Plan 19）.
 *
 * <p>在 Job 状态推进（PROCESSING / READY / FAILED）成功后，于同一事务内写入本地 {@code outbox_event}。实际发布到 Redis Streams
 * 由 crag-event publisher 异步完成；发布失败由 publisher 重试，不回滚 Job 状态。payload 只含安全字段，不泄漏文件内容、storage key、
 * 路径、Prompt、Context 或向量.
 *
 * <p>{@link JdbcOutboxEventDao} 由 {@link JdbcTemplate} 直接构造（与 Knowledge smoke 事件服务一致），避免依赖
 * crag-event 自动装配 bean 在不同上下文中的可用性差异.
 */
@Component
public class RagIngestionStatusEventWriter {

  private static final Logger log = LoggerFactory.getLogger(RagIngestionStatusEventWriter.class);

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ObjectMapper objectMapper;

  /**
   * 写入一条 ingestion 状态事件到 Outbox.
   *
   * @param job 当前 Job（携带 tenantId / knowledgeBaseId / docId / operationVersion / jobId）
   * @param eventType {@link RagIngestionStatusEventTypes} 中的事件类型
   * @param failureCategory 失败分类（仅 FAILED 事件填充，否则 null）
   * @param failureMessage 失败安全短摘要（仅 FAILED 事件填充，否则 null）
   * @return 新建 outbox 事件 ID（可用于日志关联）
   */
  public long write(
      IngestionJob job, String eventType, String failureCategory, String failureMessage) {
    long eventId = nextEventId();
    EventEnvelope envelope =
        buildEnvelope(job, eventType, failureCategory, failureMessage, eventId);
    new JdbcOutboxEventDao(jdbcTemplate).insert(envelope, Instant.now());
    log.info(
        "Ingestion status event written — docId={} operationVersion={} eventType={}",
        job.getDocId(),
        job.getOperationVersion(),
        eventType);
    return eventId;
  }

  /**
   * 组装状态事件信封（不写入 Outbox），便于单测校验字段而无需 DB.
   *
   * @param job 当前 Job
   * @param eventType 事件类型
   * @param failureCategory 失败分类（可 null）
   * @param failureMessage 失败安全短摘要（可 null）
   * @param eventId 已分配的事件 ID
   * @return 待写入的 EventEnvelope
   */
  EventEnvelope buildEnvelope(
      IngestionJob job,
      String eventType,
      String failureCategory,
      String failureMessage,
      long eventId) {
    Instant now = Instant.now();
    RagIngestionStatusPayload payload =
        new RagIngestionStatusPayload(
            job.getTenantId(),
            job.getKnowledgeBaseId(),
            job.getDocId(),
            job.getOperationVersion(),
            job.getJobId() == null ? 0L : job.getJobId(),
            statusFor(eventType),
            failureCategory,
            failureMessage,
            now.toEpochMilli());
    String payloadJson = objectMapper.writeValueAsString(payload);
    return new EventEnvelope(
        eventId,
        eventType,
        RagIngestionStatusEventTypes.PRODUCER,
        RagIngestionStatusEventTypes.RESOURCE_DOCUMENT,
        job.getDocId(),
        job.getOperationVersion(),
        RagIngestionStatusEventTypes.PAYLOAD_VERSION,
        now,
        "ingestion-" + (job.getJobId() == null ? eventId : job.getJobId()),
        payloadJson);
  }

  private static String statusFor(String eventType) {
    return switch (eventType) {
      case RagIngestionStatusEventTypes.INGESTION_PROCESSING -> "PROCESSING";
      case RagIngestionStatusEventTypes.INGESTION_READY -> "READY";
      case RagIngestionStatusEventTypes.INGESTION_FAILED -> "FAILED";
      default -> "UNKNOWN";
    };
  }

  private long nextEventId() {
    Long id = jdbcTemplate.queryForObject("SELECT nextval('rag_event_id_seq')", Long.class);
    if (id == null) {
      throw new IllegalStateException("rag_event_id_seq returned null");
    }
    return id;
  }
}
