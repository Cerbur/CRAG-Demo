package ai.cerbur.crag.knowledge.producer;

import ai.cerbur.crag.event.api.EventEnvelope;
import ai.cerbur.crag.event.jdbc.JdbcOutboxEventDao;
import ai.cerbur.crag.knowledge.core.knowledgebase.KnowledgeBaseResult;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 创建 KnowledgeBase 后在同一事务写 {@code KNOWLEDGE_BASE_CREATED} Outbox（plan_21/21.3）。
 *
 * <p>与 {@link DocUploadedOutboxWriter} 共享 {@code knowledge_event_id_seq}。Outbox 行以 PENDING 写入，实际发布由
 * {@code crag-event} publisher 在启用环境异步完成；发布失败由 publisher 重试，不回滚建库事务。业务事务回滚时， Outbox
 * 行（同事务）一并回滚，保证业务记录与事件原子一致。
 */
@Service
public class KnowledgeBaseCreatedOutboxWriter {

  @Autowired private JdbcOutboxEventDao outboxDao;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ObjectMapper objectMapper;

  /**
   * 在当前事务内写入 KNOWLEDGE_BASE_CREATED Outbox 行。
   *
   * @return 新建 outbox 事件 ID（可用于日志关联）
   */
  @Transactional
  public long write(KnowledgeBaseResult kb) {
    long eventId = nextEventId();
    KnowledgeBaseCreatedPayload payload =
        new KnowledgeBaseCreatedPayload(kb.tenantId(), kb.knowledgeBaseId(), kb.createdByUserId());
    String payloadJson = payload.toJson(objectMapper);
    EventEnvelope envelope =
        new EventEnvelope(
            eventId,
            KnowledgeEventTypes.KNOWLEDGE_BASE_CREATED,
            KnowledgeEventTypes.PRODUCER,
            KnowledgeEventTypes.RESOURCE_KNOWLEDGE_BASE,
            kb.knowledgeBaseId(),
            1L,
            KnowledgeEventTypes.PAYLOAD_VERSION,
            Instant.now(),
            "kb-" + kb.knowledgeBaseId(),
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
