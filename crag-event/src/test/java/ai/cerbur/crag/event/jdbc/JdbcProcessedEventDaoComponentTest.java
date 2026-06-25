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
      ProcessedEventRecord record = dao.findByEventId("knowledge-smoke-1", 101L);
      assertThat(record).isNotNull();
      assertThat(record.status()).isEqualTo(ProcessedEventStatus.FAILED);
      assertThat(record.handlerAttemptCount()).isZero();
    }

    @Test
    @DisplayName("duplicate consumer + eventId is rejected")
    void duplicateConsumerEventIdRejected() {
      placeholder("knowledge-smoke-1", 201L, "K:1");

      boolean again = placeholder("knowledge-smoke-1", 201L, "K:other");

      assertThat(again).isFalse();
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

      boolean processed = dao.markProcessed("knowledge-smoke-1", 501L, "1-0", Instant.now(clock));

      assertThat(processed).isTrue();
      ProcessedEventRecord record = dao.findByEventId("knowledge-smoke-1", 501L);
      assertThat(record.status()).isEqualTo(ProcessedEventStatus.PROCESSED);
      assertThat(record.processedAt()).isNotNull();
    }

    @Test
    @DisplayName("markProcessed on an already PROCESSED row is a no-op")
    void processedNotReprocessed() {
      placeholder("knowledge-smoke-1", 601L, "K:1");
      dao.markProcessed("knowledge-smoke-1", 601L, "1-0", Instant.now(clock));

      boolean again = dao.markProcessed("knowledge-smoke-1", 601L, "1-0", Instant.now(clock));

      assertThat(again).isFalse();
    }

    @Test
    @DisplayName("markFailed increments the handler attempt count")
    void markFailedIncrements() {
      placeholder("knowledge-smoke-1", 701L, "K:1");

      dao.markFailed(
          "knowledge-smoke-1", 701L, EventErrorCode.HANDLER_FAILED, "boom", Instant.now(clock));
      dao.markFailed(
          "knowledge-smoke-1", 701L, EventErrorCode.HANDLER_FAILED, "boom", Instant.now(clock));

      ProcessedEventRecord record = dao.findByEventId("knowledge-smoke-1", 701L);
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
          801L,
          EventErrorCode.HANDLER_NON_RETRYABLE,
          "fatal",
          Instant.now(clock));

      boolean processed = dao.markProcessed("knowledge-smoke-1", 801L, "1-0", Instant.now(clock));

      assertThat(processed).isFalse();
      assertThat(dao.findByEventId("knowledge-smoke-1", 801L).status())
          .isEqualTo(ProcessedEventStatus.DEAD_LETTERED);
    }

    @Test
    @DisplayName("markDeadLettered on an already PROCESSED row is ignored")
    void deadLetterIgnoredWhenProcessed() {
      placeholder("knowledge-smoke-1", 901L, "K:1");
      dao.markProcessed("knowledge-smoke-1", 901L, "1-0", Instant.now(clock));

      boolean dead =
          dao.markDeadLettered(
              "knowledge-smoke-1",
              901L,
              EventErrorCode.HANDLER_NON_RETRYABLE,
              "fatal",
              Instant.now(clock));

      assertThat(dead).isFalse();
      assertThat(dao.findByEventId("knowledge-smoke-1", 901L).status())
          .isEqualTo(ProcessedEventStatus.PROCESSED);
    }
  }
}
