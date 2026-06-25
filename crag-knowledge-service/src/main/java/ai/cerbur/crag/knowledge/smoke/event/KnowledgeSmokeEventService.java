package ai.cerbur.crag.knowledge.smoke.event;

import ai.cerbur.crag.event.api.EventEnvelope;
import ai.cerbur.crag.event.api.OutboxEventStatus;
import ai.cerbur.crag.event.api.ProcessedEventStatus;
import ai.cerbur.crag.event.jdbc.JdbcOutboxEventDao;
import ai.cerbur.crag.event.jdbc.JdbcProcessedEventDao;
import ai.cerbur.crag.event.jdbc.OutboxEventRecord;
import ai.cerbur.crag.event.jdbc.ProcessedEventRecord;
import ai.cerbur.crag.knowledge.smoke.dto.KnowledgeSmokeEventResponse;
import ai.cerbur.crag.knowledge.smoke.dto.KnowledgeSmokeEventStatusResponse;
import ai.cerbur.crag.knowledge.smoke.dto.KnowledgeSmokeFailMode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes smoke events into the Knowledge outbox and reads back diagnostic summaries.
 *
 * <p>The smoke event uses {@code trace_id = runId} so the {@code runId} query is a cheap indexed
 * lookup, and the payload carries the controlled {@code failMode} the handler reads. Event ids are
 * minted by a local timestamp+counter generator; this is smoke-only and not the production
 * Snowflake path (the Knowledge service does not register Snowflake entities).
 */
@Component
@Profile("smoke")
public class KnowledgeSmokeEventService {

  private static final String PRODUCER = "knowledge-service";
  private static final String RESOURCE_TYPE = "SMOKE_EVENT";
  private static final String EVENT_TYPE = "EVENT_SMOKE_CREATED";

  private final JdbcOutboxEventDao outboxDao;
  private final JdbcProcessedEventDao processedDao;
  private final NamedParameterJdbcTemplate namedJdbc;
  private final ObjectMapper objectMapper;
  private final String consumerName;
  private final AtomicLong counter = new AtomicLong();

  public KnowledgeSmokeEventService(
      JdbcTemplate jdbcTemplate,
      ObjectMapper objectMapper,
      @Value("${crag.event.consumer.consumer-name:knowledge-smoke-1}") String consumerName) {
    this.outboxDao = new JdbcOutboxEventDao(jdbcTemplate);
    this.processedDao = new JdbcProcessedEventDao(jdbcTemplate);
    this.namedJdbc = new NamedParameterJdbcTemplate(jdbcTemplate);
    this.objectMapper = objectMapper;
    this.consumerName = consumerName;
  }

  /** Inserts a new smoke event in PENDING state and returns its decimal-string id. */
  @Transactional
  public KnowledgeSmokeEventResponse createEvent(
      String runId, String message, KnowledgeSmokeFailMode failMode) {
    long eventId = nextEventId();
    EventEnvelope envelope =
        new EventEnvelope(
            eventId,
            EVENT_TYPE,
            PRODUCER,
            RESOURCE_TYPE,
            eventId,
            1L,
            1,
            Instant.now(),
            runId,
            buildPayload(runId, message, failMode));
    outboxDao.insert(envelope, Instant.now());
    return new KnowledgeSmokeEventResponse(
        Long.toString(eventId), runId, OutboxEventStatus.PENDING.name());
  }

  /** Returns the diagnostic summary for a single event. */
  public KnowledgeSmokeEventStatusResponse statusByEventId(long eventId) {
    OutboxEventRecord outbox = outboxDao.findById(eventId);
    if (outbox == null) {
      throw new IllegalArgumentException("event not found: " + eventId);
    }
    return toStatus(outbox);
  }

  /** Returns the diagnostic summaries for all events in a run, ordered by event id. */
  public List<KnowledgeSmokeEventStatusResponse> statusByRunId(String runId) {
    List<Long> ids =
        namedJdbc.queryForList(
            "SELECT event_id FROM outbox_event WHERE trace_id = :runId ORDER BY event_id",
            new MapSqlParameterSource("runId", runId),
            Long.class);
    return ids.stream()
        .map(outboxDao::findById)
        .filter(record -> record != null)
        .map(this::toStatus)
        .toList();
  }

  private KnowledgeSmokeEventStatusResponse toStatus(OutboxEventRecord outbox) {
    ProcessedEventRecord processed = processedDao.findByEventId(consumerName, outbox.eventId());
    return new KnowledgeSmokeEventStatusResponse(
        Long.toString(outbox.eventId()),
        outbox.traceId(),
        outbox.status().name(),
        processed == null ? null : processed.status().name(),
        processed == null ? 0 : processed.handlerAttemptCount(),
        processed != null && processed.status() == ProcessedEventStatus.DEAD_LETTERED,
        outbox.lastErrorCode(),
        outbox.lastErrorMessage());
  }

  private String buildPayload(String runId, String message, KnowledgeSmokeFailMode failMode) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("runId", runId);
    payload.put("message", message == null ? "" : message);
    payload.put("failMode", failMode.name());
    try {
      return objectMapper.writeValueAsString(payload);
    } catch (RuntimeException e) {
      throw new IllegalStateException("failed to serialize smoke payload", e);
    }
  }

  private long nextEventId() {
    long time = System.currentTimeMillis();
    long sequence = counter.incrementAndGet() & 0xFFF;
    return (time << 12) | sequence;
  }
}
