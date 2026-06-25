package ai.cerbur.crag.event.jdbc;

import ai.cerbur.crag.event.api.EventEnvelope;
import ai.cerbur.crag.event.api.EventErrorCode;
import ai.cerbur.crag.event.api.OutboxEventStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * JDBC access to the local {@code outbox_event} table.
 *
 * <p>The publisher calls {@link #claimBatch(String, int, Duration, Instant)} to atomically claim a
 * batch (select candidates then per-row version CAS — sanctioned逐条 CAS because each row's claim
 * must be judged individually), then {@link #markPublished}, {@link #markRetryWait} or {@link
 * #markDead} with the claimed version. Mark methods perform version CAS: a zero-affected update
 * means another publisher reclaimed the row and throws {@link OutboxCasConflictException}.
 */
public class JdbcOutboxEventDao {

  private final NamedParameterJdbcTemplate jdbc;

  public JdbcOutboxEventDao(JdbcTemplate jdbcTemplate) {
    this.jdbc = new NamedParameterJdbcTemplate(jdbcTemplate);
  }

  /** Inserts a new event in {@code PENDING} state with version 0 and attempt count 0. */
  public void insert(EventEnvelope envelope, Instant now) {
    Timestamp ts = Timestamp.from(now);
    MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue("eventId", envelope.eventId())
            .addValue("eventType", envelope.eventType())
            .addValue("producer", envelope.producer())
            .addValue("resourceType", envelope.resourceType())
            .addValue("resourceId", envelope.resourceId())
            .addValue("operationVersion", envelope.operationVersion())
            .addValue("payloadVersion", envelope.payloadVersion())
            .addValue("payloadJson", envelope.payload())
            .addValue("traceId", envelope.traceId())
            .addValue("occurredAt", Timestamp.from(envelope.occurredAt()))
            .addValue("now", ts);
    jdbc.update(
        "INSERT INTO outbox_event "
            + "(event_id, event_type, producer, resource_type, resource_id, operation_version, "
            + "payload_version, payload_json, trace_id, occurred_at, status, next_attempt_at, "
            + "attempt_count, version, created_at, updated_at) "
            + "VALUES "
            + "(:eventId, :eventType, :producer, :resourceType, :resourceId, :operationVersion, "
            + ":payloadVersion, :payloadJson, :traceId, :occurredAt, 'PENDING', :now, "
            + "0, 0, :now, :now)",
        params);
  }

  /**
   * Claims up to {@code batchSize} claimable events (PENDING, due RETRY_WAIT, or stale PUBLISHING)
   * for {@code publisher}. Each candidate is claimed by a version CAS; only winners are returned,
   * re-read with their post-claim version so the caller can mark them.
   */
  public List<OutboxEventRecord> claimBatch(
      String publisher, int batchSize, Duration claimDuration, Instant now) {
    Timestamp nowTs = Timestamp.from(now);
    List<Map<String, Object>> candidates =
        jdbc.queryForList(
            "SELECT event_id, version FROM outbox_event "
                + "WHERE status = 'PENDING' "
                + "   OR (status = 'RETRY_WAIT' AND next_attempt_at <= :now) "
                + "   OR (status = 'PUBLISHING' AND claimed_until < :now) "
                + "ORDER BY COALESCE(next_attempt_at, occurred_at), event_id "
                + "LIMIT :batchSize",
            Map.of("now", nowTs, "batchSize", batchSize));
    Timestamp claimUntil = Timestamp.from(now.plus(claimDuration));
    List<OutboxEventRecord> claimed = new ArrayList<>();
    for (Map<String, Object> candidate : candidates) {
      long eventId = ((Number) candidate.get("event_id")).longValue();
      long version = ((Number) candidate.get("version")).longValue();
      int affected =
          jdbc.update(
              "UPDATE outbox_event "
                  + "SET status = 'PUBLISHING', version = version + 1, claimed_by = :publisher, "
                  + "claimed_until = :claimUntil, updated_at = :now "
                  + "WHERE event_id = :eventId AND version = :version "
                  + "AND (status = 'PENDING' OR status = 'RETRY_WAIT' "
                  + "OR (status = 'PUBLISHING' AND claimed_until < :now))",
              new MapSqlParameterSource()
                  .addValue("publisher", publisher)
                  .addValue("claimUntil", claimUntil)
                  .addValue("now", nowTs)
                  .addValue("eventId", eventId)
                  .addValue("version", version));
      if (affected > 0) {
        OutboxEventRecord record = findById(eventId);
        if (record != null) {
          claimed.add(record);
        }
      }
    }
    return claimed;
  }

  /** Returns the row for {@code eventId}, or {@code null} when absent. */
  public OutboxEventRecord findById(long eventId) {
    List<OutboxEventRecord> rows =
        jdbc.query(
            "SELECT * FROM outbox_event WHERE event_id = :eventId",
            Map.of("eventId", eventId),
            OutboxEventRowMapper.INSTANCE);
    return rows.isEmpty() ? null : rows.get(0);
  }

  /** Marks a claimed event published. Throws on version-CAS loss. */
  public void markPublished(long eventId, long expectedVersion, Instant now) {
    int affected =
        jdbc.update(
            "UPDATE outbox_event "
                + "SET status = 'PUBLISHED', version = version + 1, published_at = :now, "
                + "claimed_by = NULL, claimed_until = NULL, updated_at = :now "
                + "WHERE event_id = :eventId AND version = :version AND status = 'PUBLISHING'",
            new MapSqlParameterSource()
                .addValue("now", Timestamp.from(now))
                .addValue("eventId", eventId)
                .addValue("version", expectedVersion));
    if (affected == 0) {
      throw new OutboxCasConflictException(
          "markPublished lost CAS for eventId=" + eventId + " version=" + expectedVersion);
    }
  }

  /** Returns a claimed event to RETRY_WAIT and records the failure for the next attempt. */
  public void markRetryWait(
      long eventId,
      long expectedVersion,
      EventErrorCode errorCode,
      String errorMessage,
      Instant nextAttemptAt,
      Instant now) {
    int affected =
        jdbc.update(
            "UPDATE outbox_event "
                + "SET status = 'RETRY_WAIT', version = version + 1, "
                + "attempt_count = attempt_count + 1, last_error_code = :errorCode, "
                + "last_error_message = :errorMessage, next_attempt_at = :nextAttemptAt, "
                + "claimed_by = NULL, claimed_until = NULL, updated_at = :now "
                + "WHERE event_id = :eventId AND version = :version AND status = 'PUBLISHING'",
            new MapSqlParameterSource()
                .addValue("errorCode", errorCode.name())
                .addValue("errorMessage", errorMessage)
                .addValue("nextAttemptAt", Timestamp.from(nextAttemptAt))
                .addValue("now", Timestamp.from(now))
                .addValue("eventId", eventId)
                .addValue("version", expectedVersion));
    if (affected == 0) {
      throw new OutboxCasConflictException(
          "markRetryWait lost CAS for eventId=" + eventId + " version=" + expectedVersion);
    }
  }

  /** Moves a claimed event to DEAD after exhausting publish attempts. */
  public void markDead(
      long eventId,
      long expectedVersion,
      EventErrorCode errorCode,
      String errorMessage,
      Instant now) {
    int affected =
        jdbc.update(
            "UPDATE outbox_event "
                + "SET status = 'DEAD', version = version + 1, last_error_code = :errorCode, "
                + "last_error_message = :errorMessage, updated_at = :now "
                + "WHERE event_id = :eventId AND version = :version AND status = 'PUBLISHING'",
            new MapSqlParameterSource()
                .addValue("errorCode", errorCode.name())
                .addValue("errorMessage", errorMessage)
                .addValue("now", Timestamp.from(now))
                .addValue("eventId", eventId)
                .addValue("version", expectedVersion));
    if (affected == 0) {
      throw new OutboxCasConflictException(
          "markDead lost CAS for eventId=" + eventId + " version=" + expectedVersion);
    }
  }

  private static final class OutboxEventRowMapper implements RowMapper<OutboxEventRecord> {
    static final OutboxEventRowMapper INSTANCE = new OutboxEventRowMapper();

    @Override
    public OutboxEventRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
      return new OutboxEventRecord(
          rs.getLong("event_id"),
          rs.getLong("version"),
          rs.getString("event_type"),
          rs.getString("producer"),
          rs.getString("resource_type"),
          rs.getLong("resource_id"),
          rs.getLong("operation_version"),
          rs.getInt("payload_version"),
          instant(rs, "occurred_at"),
          rs.getString("trace_id"),
          rs.getString("payload_json"),
          OutboxEventStatus.valueOf(rs.getString("status")),
          rs.getInt("attempt_count"),
          rs.getString("last_error_code"),
          rs.getString("last_error_message"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
      Timestamp ts = rs.getTimestamp(column);
      return ts == null ? null : ts.toInstant();
    }
  }
}
