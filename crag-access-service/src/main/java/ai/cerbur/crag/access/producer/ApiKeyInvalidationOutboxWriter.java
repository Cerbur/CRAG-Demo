package ai.cerbur.crag.access.producer;

import ai.cerbur.crag.access.metrics.AccessMetrics;
import ai.cerbur.crag.event.api.EventEnvelope;
import ai.cerbur.crag.event.jdbc.JdbcOutboxEventDao;
import ai.cerbur.crag.id.api.CragIdGenerator;
import ai.cerbur.crag.id.api.IdEntityType;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 在当前事务内写入真实 {@code API_KEY_INVALIDATED} Outbox 行。
 *
 * <p>event_id 由 {@code CragIdGenerator(ACCESS_EVENT)} 分配；Outbox 行以 PENDING 写入，实际发布由 {@code
 * crag-event} publisher 在启用环境异步完成。业务事务回滚时事件一并回滚。payload 只含 Key/Scope 定位与版本，不含完整 Key 或 HMAC。
 */
@Service
public class ApiKeyInvalidationOutboxWriter {

  @Autowired private JdbcOutboxEventDao outboxDao;
  @Autowired private CragIdGenerator idGenerator;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private AccessMetrics metrics;

  /**
   * 写入一条 PENDING Outbox 事件。
   *
   * @return 新建 outbox 事件 ID（可用于日志关联）
   */
  @Transactional
  public long write(ApiKeyInvalidatedPayload payload, String traceId, Instant occurredAt) {
    long eventId = idGenerator.nextId(IdEntityType.ACCESS_EVENT);
    String payloadJson = objectMapper.writeValueAsString(payload);
    EventEnvelope envelope =
        new EventEnvelope(
            eventId,
            AccessEventTypes.API_KEY_INVALIDATED,
            AccessEventTypes.PRODUCER,
            payload.resourceType(),
            payload.resourceId(),
            payload.resourceVersion(),
            AccessEventTypes.PAYLOAD_VERSION,
            occurredAt,
            traceId,
            payloadJson);
    outboxDao.insert(envelope, occurredAt);
    metrics.apiKeyInvalidationPublished();
    return eventId;
  }
}
