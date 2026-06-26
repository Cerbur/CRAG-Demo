package ai.cerbur.crag.ingestion.consumer;

import ai.cerbur.crag.event.api.EventEnvelope;
import ai.cerbur.crag.event.api.EventHandler;
import ai.cerbur.crag.event.api.EventHandlerResult;
import ai.cerbur.crag.ingestion.job.IngestionJobResolution;
import ai.cerbur.crag.ingestion.job.IngestionJobService;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * RAG 消费 {@code DOC_UPLOADED} 的 {@link EventHandler}（Plan 19）.
 *
 * <p>双层幂等：crag-event 的 {@code processed_event}（{@code (consumer_name, event_id)} 与幂等键）保证同一投递不重复处理；
 * 业务层 {@code ingestion_job(doc_id, operation_version)} 保证重复事件不重复建 Job。本类只负责 payload 解析、幂等解析与
 * outcome 映射；文件读取、校验与切分由 19.3 的编排接入.
 *
 * <p>outcome 语义：
 *
 * <ul>
 *   <li>非 {@code DOC_UPLOADED} 事件（如 RAG 自发布的 {@code INGESTION_*} 或 Knowledge smoke 事件）→ {@code
 *       success}， ACK 以保持消费组干净；
 *   <li>非法 payload → {@code nonRetryableFailure}（安全 DLQ，不泄漏文件内容）；
 *   <li>Job 解析遇到瞬时异常（如 DB 不可达）→ {@code retryableFailure}（留 Pending 等 reclaim）；
 *   <li>正常 → {@code success}。
 * </ul>
 *
 * <p>真实 Redis Streams 的 Pending reclaim 与 DLQ 行为由 {@code crag-event} 提供并通过 Docker HTTP
 * 回归证明（plan_19.7）.
 */
@Component
@Profile("smoke")
public class DocUploadedEventHandler implements EventHandler {

  private static final Logger log = LoggerFactory.getLogger(DocUploadedEventHandler.class);
  private static final Set<String> EVENT_TYPES = Set.of("DOC_UPLOADED");

  private final ObjectMapper objectMapper;
  private final IngestionJobService ingestionJobService;
  private final String streamKey;
  private final String groupName;
  private final String consumerName;

  public DocUploadedEventHandler(
      ObjectMapper objectMapper,
      IngestionJobService ingestionJobService,
      @Value("${crag.event.stream-key:crag:event:knowledge}") String streamKey,
      @Value("${crag.event.group-name:rag-ingestion}") String groupName,
      @Value("${crag.event.consumer.consumer-name:rag-ingestion-1}") String consumerName) {
    this.objectMapper = objectMapper;
    this.ingestionJobService = ingestionJobService;
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
    return EVENT_TYPES;
  }

  @Override
  public EventHandlerResult handle(EventEnvelope envelope) {
    if (!EVENT_TYPES.contains(envelope.eventType())) {
      // 非 DOC_UPLOADED 事件：ACK 以保持消费组干净（例如 RAG 自发布的 INGESTION_* 状态事件）。
      return EventHandlerResult.success();
    }

    DocUploadedPayload payload;
    try {
      payload = DocUploadedPayload.parse(envelope.payload(), objectMapper);
    } catch (InvalidDocUploadedPayloadException e) {
      log.warn(
          "DOC_UPLOADED payload invalid, dead-lettering — docId(resourceId)={} reason={}",
          envelope.resourceIdAsString(),
          e.getMessage());
      return EventHandlerResult.nonRetryableFailure("invalid DOC_UPLOADED payload");
    }

    try {
      IngestionJobResolution resolution =
          ingestionJobService.resolve(
              payload.tenantId(),
              payload.knowledgeBaseId(),
              payload.docId(),
              payload.operationVersion(),
              payload.fileType(),
              payload.sizeBytes(),
              payload.sha256());
      log.info(
          "DOC_UPLOADED resolved — docId={} operationVersion={} fresh={} needsProcessing={}",
          payload.docId(),
          payload.operationVersion(),
          resolution.fresh(),
          resolution.needsProcessing());
      return EventHandlerResult.success();
    } catch (RuntimeException e) {
      // 瞬时异常（如 DB 不可达）：不 ACK，留 Pending 由 reclaim 重新投递。
      log.warn(
          "DOC_UPLOADED resolve failed, will retry — docId={} reason={}",
          payload.docId(),
          e.getMessage());
      return EventHandlerResult.retryableFailure("DOC_UPLOADED resolve failed");
    }
  }
}
