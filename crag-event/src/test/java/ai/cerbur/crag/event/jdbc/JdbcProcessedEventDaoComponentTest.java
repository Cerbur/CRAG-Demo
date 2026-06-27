package ai.cerbur.crag.event.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import ai.cerbur.crag.event.api.EventErrorCode;
import ai.cerbur.crag.event.api.ProcessedEventStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(classes = EventJdbcTestConfiguration.class)
@Transactional
@DisplayName("JdbcProcessedEventDao")
class JdbcProcessedEventDaoComponentTest {

  @Autowired private JdbcTemplate jdbcTemplate;

  private JdbcProcessedEventDao dao;
  private final Clock clock = Clock.fixed(Instant.parse("2026-06-25T12:00:00Z"), ZoneOffset.UTC);

  @BeforeEach
  void setUp() {
    dao = new JdbcProcessedEventDao(jdbcTemplate);
  }

  private boolean placeholder(String consumer, long eventId, String idempotencyKey) {
    return dao.insertPlaceholder(
        consumer,
        EventTestFixtures.envelope(eventId),
        idempotencyKey,
        "crag:event:knowledge",
        "1-0",
        Instant.now(clock));
  }

  @Nested
  @DisplayName("idempotency")
  class Idempotency {

    @Test
    @DisplayName("first placeholder insert succeeds and starts FAILED")
    void firstInsertIsFailed() {
      boolean inserted =
          placeholder("knowledge-smoke-1", 101L, "EVENT_SMOKE_CREATED:SMOKE_EVENT:1:1");

      assertThat(inserted).isTrue();
      ProcessedEventRecord record =
          dao.findByIdempotencyKey("knowledge-smoke-1", "EVENT_SMOKE_CREATED:SMOKE_EVENT:1:1");
      assertThat(record).isNotNull();
      assertThat(record.status()).isEqualTo(ProcessedEventStatus.FAILED);
      assertThat(record.handlerAttemptCount()).isZero();
    }

    @Test
    @DisplayName(
        "same eventId with different idempotencyKey (two producers on one stream) is accepted")
    void sameEventIdDifferentIdempotencyKeyAccepted() {
      // Two producers (Knowledge DOC_UPLOADED, RAG INGESTION_*) each use their own outbox event_id
      // sequence, so they can publish events sharing an event id on one Redis stream. The consumer
      // must de-dupe by idempotency key (the event's logical identity), never by event id, or the
      // second event is silently dropped and its handler never runs.
      boolean first = placeholder("rag-ingestion-1", 201L, "DOC_UPLOADED:DOCUMENT:10:1");
      boolean second = placeholder("rag-ingestion-1", 201L, "INGESTION_READY:DOCUMENT:9:1");

      assertThat(first).isTrue();
      assertThat(second).as("different idempotency key must not be de-duped by event id").isTrue();
    }

    @Test
    @DisplayName("duplicate consumer + idempotencyKey is rejected even with a new eventId")
    void duplicateIdempotencyKeyRejected() {
      placeholder("knowledge-smoke-1", 301L, "EVENT_SMOKE_CREATED:SMOKE_EVENT:1:1");

      boolean again = placeholder("knowledge-smoke-1", 302L, "EVENT_SMOKE_CREATED:SMOKE_EVENT:1:1");

      assertThat(again).isFalse();
    }

    @Test
    @DisplayName("different consumers may process the same eventId")
    void differentConsumersShareEventId() {
      placeholder("knowledge-smoke-1", 401L, "K:1");
      boolean other = placeholder("knowledge-smoke-2", 401L, "K:1");

      assertThat(other).isTrue();
    }
  }

  @Nested
  @DisplayName("state transitions")
  class StateTransitions {

    @Test
    @DisplayName("FAILED can be promoted to PROCESSED")
    void failedToProcessed() {
      placeholder("knowledge-smoke-1", 501L, "K:1");

      boolean processed = dao.markProcessed("knowledge-smoke-1", "K:1", "1-0", Instant.now(clock));

      assertThat(processed).isTrue();
      ProcessedEventRecord record = dao.findByIdempotencyKey("knowledge-smoke-1", "K:1");
      assertThat(record.status()).isEqualTo(ProcessedEventStatus.PROCESSED);
      assertThat(record.processedAt()).isNotNull();
    }

    @Test
    @DisplayName("markProcessed on an already PROCESSED row is a no-op")
    void processedNotReprocessed() {
      placeholder("knowledge-smoke-1", 601L, "K:1");
      dao.markProcessed("knowledge-smoke-1", "K:1", "1-0", Instant.now(clock));

      boolean again = dao.markProcessed("knowledge-smoke-1", "K:1", "1-0", Instant.now(clock));

      assertThat(again).isFalse();
    }

    @Test
    @DisplayName("markFailed increments the handler attempt count")
    void markFailedIncrements() {
      placeholder("knowledge-smoke-1", 701L, "K:1");

      dao.markFailed(
          "knowledge-smoke-1", "K:1", EventErrorCode.HANDLER_FAILED, "boom", Instant.now(clock));
      dao.markFailed(
          "knowledge-smoke-1", "K:1", EventErrorCode.HANDLER_FAILED, "boom", Instant.now(clock));

      ProcessedEventRecord record = dao.findByIdempotencyKey("knowledge-smoke-1", "K:1");
      assertThat(record.status()).isEqualTo(ProcessedEventStatus.FAILED);
      assertThat(record.handlerAttemptCount()).isEqualTo(2);
      assertThat(record.lastErrorCode()).isEqualTo("HANDLER_FAILED");
    }

    @Test
    @DisplayName("DEAD_LETTERED is not overwritten by a later markProcessed")
    void deadLetteredNotOverwritten() {
      placeholder("knowledge-smoke-1", 801L, "K:1");
      dao.markDeadLettered(
          "knowledge-smoke-1",
          "K:1",
          EventErrorCode.HANDLER_NON_RETRYABLE,
          "fatal",
          Instant.now(clock));

      boolean processed = dao.markProcessed("knowledge-smoke-1", "K:1", "1-0", Instant.now(clock));

      assertThat(processed).isFalse();
      assertThat(dao.findByIdempotencyKey("knowledge-smoke-1", "K:1").status())
          .isEqualTo(ProcessedEventStatus.DEAD_LETTERED);
    }

    @Test
    @DisplayName("markDeadLettered on an already PROCESSED row is ignored")
    void deadLetterIgnoredWhenProcessed() {
      placeholder("knowledge-smoke-1", 901L, "K:1");
      dao.markProcessed("knowledge-smoke-1", "K:1", "1-0", Instant.now(clock));

      boolean dead =
          dao.markDeadLettered(
              "knowledge-smoke-1",
              "K:1",
              EventErrorCode.HANDLER_NON_RETRYABLE,
              "fatal",
              Instant.now(clock));

      assertThat(dead).isFalse();
      assertThat(dao.findByIdempotencyKey("knowledge-smoke-1", "K:1").status())
          .isEqualTo(ProcessedEventStatus.PROCESSED);
    }
  }
}
