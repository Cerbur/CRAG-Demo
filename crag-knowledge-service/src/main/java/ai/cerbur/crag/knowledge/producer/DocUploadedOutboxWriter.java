package ai.cerbur.crag.knowledge.producer;

import ai.cerbur.crag.event.api.EventEnvelope;
import ai.cerbur.crag.event.jdbc.JdbcOutboxEventDao;
import ai.cerbur.crag.knowledge.core.document.DocumentResult;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 上传成功后在同事务写入真实 {@code DOC_UPLOADED} Outbox。
 *
 * <p>event_id 取 Knowledge 本地序列 {@code knowledge_event_id_seq}（plan_18 文件边界不含 crag-id）。Outbox 行以
 * PENDING 写入， 实际发布由 {@code crag-event} publisher 在启用环境异步完成；发布失败由 publisher 重试，不回滚上传事务。
 */
@Service
public class DocUploadedOutboxWriter {

  @Autowired private JdbcOutboxEventDao outboxDao;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ObjectMapper objectMapper;

  /**
   * 在当前事务内写入 DOC_UPLOADED Outbox 行。
   *
   * @return 新建 outbox 事件 ID（可用于日志关联）
   */
  @Transactional
  public long write(DocumentResult doc) {
    long eventId = nextEventId();
    DocumentUploadedPayload payload =
        new DocumentUploadedPayload(
            doc.tenantId(),
            doc.knowledgeBaseId(),
            doc.docId(),
            doc.operationVersion(),
            doc.fileType().name(),
            doc.sizeBytes(),
            doc.sha256());
    String payloadJson = objectMapper.writeValueAsString(payload);
    EventEnvelope envelope =
        new EventEnvelope(
            eventId,
            KnowledgeEventTypes.DOC_UPLOADED,
            KnowledgeEventTypes.PRODUCER,
            KnowledgeEventTypes.RESOURCE_DOCUMENT,
            doc.docId(),
            doc.operationVersion(),
            KnowledgeEventTypes.PAYLOAD_VERSION,
            Instant.now(),
            "doc-" + doc.docId(),
            payloadJson);
    outboxDao.insert(envelope, Instant.now());
    return eventId;
  }

  private long nextEventId() {
    Long id = jdbcTemplate.queryForObject("SELECT nextval('knowledge_event_id_seq')", Long.class);
    if (id == null) {
      throw new IllegalStateException("knowledge_event_id_seq returned null");
    }
    return id;
  }
}
