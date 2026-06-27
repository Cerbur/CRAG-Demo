package ai.cerbur.crag.event.jdbc;

import ai.cerbur.crag.event.api.EventEnvelope;
import ai.cerbur.crag.event.api.EventErrorCode;
import ai.cerbur.crag.event.api.ProcessedEventStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * JDBC access to the local {@code processed_event} table, backing consumer idempotency.
 *
 * <p>The primary key {@code (consumer_name, idempotency_key)} makes {@link #insertPlaceholder} the
 * idempotency gate: a true duplicate of the same logical event returns {@code false} so the caller
 * can re-check the existing status. Idempotency is by the event's logical identity, never by {@code
 * event_id}, which several producers can independently re-use on one shared Redis stream. State
 * transitions are status-guarded rather than version-CAS, so {@code PROCESSED} and {@code
 * DEAD_LETTERED} rows are never overwritten by the ordinary success path.
 */
public class JdbcProcessedEventDao {

  private final NamedParameterJdbcTemplate jdbc;

  public JdbcProcessedEventDao(JdbcTemplate jdbcTemplate) {
    this.jdbc = new NamedParameterJdbcTemplate(jdbcTemplate);
  }

  /**
   * Inserts a {@code FAILED} placeholder for an unseen event. Returns {@code false} when a row with
   * the same {@code (consumer_name, idempotency_key)} already exists — a true duplicate of the same
   * logical event — so the caller can re-read the existing status to decide whether to skip or
   * re-process. Two different events sharing an {@code event_id} (different producers on one
   * stream) have different idempotency keys and are both accepted.
   */
  public boolean insertPlaceholder(
      String consumerName,
      EventEnvelope envelope,
      String idempotencyKey,
      String streamKey,
      String streamRecordId,
      Instant now) {
    try {
      jdbc.update(
          "INSERT INTO processed_event "
              + "(consumer_name, event_id, idempotency_key, event_type, resource_type, resource_id, "
              + "operation_version, stream_key, stream_record_id, first_seen_at, status, "
              + "handler_attempt_count) "
              + "VALUES "
              + "(:consumerName, :eventId, :idempotencyKey, :eventType, :resourceType, :resourceId, "
              + ":operationVersion, :streamKey, :streamRecordId, :now, 'FAILED', 0)",
          new MapSqlParameterSource()
              .addValue("consumerName", consumerName)
              .addValue("eventId", envelope.eventId())
              .addValue("idempotencyKey", idempotencyKey)
              .addValue("eventType", envelope.eventType())
              .addValue("resourceType", envelope.resourceType())
              .addValue("resourceId", envelope.resourceId())
              .addValue("operationVersion", envelope.operationVersion())
              .addValue("streamKey", streamKey)
              .addValue("streamRecordId", streamRecordId)
              .addValue("now", Timestamp.from(now)));
      return true;
    } catch (DuplicateKeyException e) {
      return false;
    }
  }

  /**
   * Returns the row for {@code (consumerName, idempotencyKey)}, or {@code null} when absent.
   * Lookups are by idempotency key (the event's logical identity and the table's de-dup key), never
   * by {@code event_id}, which is not unique across producers sharing a stream.
   */
  public ProcessedEventRecord findByIdempotencyKey(String consumerName, String idempotencyKey) {
    List<ProcessedEventRecord> rows =
        jdbc.query(
            "SELECT * FROM processed_event "
                + "WHERE consumer_name = :consumerName AND idempotency_key = :idempotencyKey",
            new MapSqlParameterSource()
                .addValue("consumerName", consumerName)
                .addValue("idempotencyKey", idempotencyKey),
            ProcessedEventRowMapper.INSTANCE);
    return rows.isEmpty() ? null : rows.get(0);
  }

  /**
   * Marks a {@code FAILED} row processed. Returns {@code false} (no-op) when the row is already
   * terminal or absent, so re-delivery of a processed or dead-lettered event does not clobber it.
   */
  public boolean markProcessed(
      String consumerName, String idempotencyKey, String streamRecordId, Instant now) {
    int affected =
        jdbc.update(
            "UPDATE processed_event "
                + "SET status = 'PROCESSED', processed_at = :now, stream_record_id = :streamRecordId, "
                + "last_error_code = NULL, last_error_message = NULL "
                + "WHERE consumer_name = :consumerName AND idempotency_key = :idempotencyKey "
                + "AND status = 'FAILED'",
            new MapSqlParameterSource()
                .addValue("now", Timestamp.from(now))
                .addValue("streamRecordId", streamRecordId)
                .addValue("consumerName", consumerName)
                .addValue("idempotencyKey", idempotencyKey));
    return affected > 0;
  }

  /**
   * Records a handler failure: increments the attempt count and stays/reverts to {@code FAILED}.
   * Terminal rows ({@code PROCESSED}, {@code DEAD_LETTERED}) are left untouched.
   */
  public boolean markFailed(
      String consumerName,
      String idempotencyKey,
      EventErrorCode errorCode,
      String errorMessage,
      Instant now) {
    int affected =
        jdbc.update(
            "UPDATE processed_event "
                + "SET status = 'FAILED', handler_attempt_count = handler_attempt_count + 1, "
                + "last_error_code = :errorCode, last_error_message = :errorMessage "
                + "WHERE consumer_name = :consumerName AND idempotency_key = :idempotencyKey "
                + "AND status NOT IN ('PROCESSED', 'DEAD_LETTERED')",
            new MapSqlParameterSource()
                .addValue("errorCode", errorCode.name())
                .addValue("errorMessage", errorMessage)
                .addValue("consumerName", consumerName)
                .addValue("idempotencyKey", idempotencyKey));
    return affected > 0;
  }

  /**
   * Moves a non-terminal row to {@code DEAD_LETTERED}. A {@code PROCESSED} row is never
   * overwritten, so a late dead-letter for an already-processed event is ignored.
   */
  public boolean markDeadLettered(
      String consumerName,
      String idempotencyKey,
      EventErrorCode errorCode,
      String errorMessage,
      Instant now) {
    int affected =
        jdbc.update(
            "UPDATE processed_event "
                + "SET status = 'DEAD_LETTERED', last_error_code = :errorCode, "
                + "last_error_message = :errorMessage "
                + "WHERE consumer_name = :consumerName AND idempotency_key = :idempotencyKey "
                + "AND status <> 'PROCESSED'",
            new MapSqlParameterSource()
                .addValue("errorCode", errorCode.name())
                .addValue("errorMessage", errorMessage)
                .addValue("consumerName", consumerName)
                .addValue("idempotencyKey", idempotencyKey));
    return affected > 0;
  }

  private static final class ProcessedEventRowMapper implements RowMapper<ProcessedEventRecord> {
    static final ProcessedEventRowMapper INSTANCE = new ProcessedEventRowMapper();

    @Override
    public ProcessedEventRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
      return new ProcessedEventRecord(
          rs.getString("consumer_name"),
          rs.getLong("event_id"),
          rs.getString("idempotency_key"),
          rs.getString("event_type"),
          rs.getString("resource_type"),
          rs.getLong("resource_id"),
          rs.getLong("operation_version"),
          rs.getString("stream_key"),
          rs.getString("stream_record_id"),
          instant(rs, "first_seen_at"),
          instant(rs, "processed_at"),
          ProcessedEventStatus.valueOf(rs.getString("status")),
          rs.getInt("handler_attempt_count"),
          rs.getString("last_error_code"),
          rs.getString("last_error_message"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
      Timestamp ts = rs.getTimestamp(column);
      return ts == null ? null : ts.toInstant();
    }
  }
}
