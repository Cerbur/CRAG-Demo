package ai.cerbur.crag.event.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.cerbur.crag.event.api.EventErrorCode;
import ai.cerbur.crag.event.api.OutboxEventStatus;
import java.time.Clock;
import java.time.Duration;
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
@DisplayName("JdbcOutboxEventDao")
class JdbcOutboxEventDaoComponentTest {

  @Autowired private JdbcTemplate jdbcTemplate;

  private JdbcOutboxEventDao dao;
  private final Clock clock = Clock.fixed(Instant.parse("2026-06-25T12:00:00Z"), ZoneOffset.UTC);

  @BeforeEach
  void setUp() {
    dao = new JdbcOutboxEventDao(jdbcTemplate);
  }

  private void insert(long eventId) {
    dao.insert(EventTestFixtures.envelope(eventId), Instant.now(clock));
  }

  @Nested
  @DisplayName("insert and find")
  class InsertAndFind {

    @Test
    @DisplayName("inserted event is PENDING with version 0")
    void insertedEventIsPending() {
      insert(101L);

      OutboxEventRecord record = dao.findById(101L);

      assertThat(record).isNotNull();
      assertThat(record.status()).isEqualTo(OutboxEventStatus.PENDING);
      assertThat(record.version()).isZero();
      assertThat(record.attemptCount()).isZero();
    }
  }

  @Nested
  @DisplayName("claim")
  class Claim {

    @Test
    @DisplayName("claimBatch moves PENDING events to PUBLISHING and bumps version")
    void claimMovesPendingToPublishing() {
      insert(201L);
      insert(202L);

      var claimed = dao.claimBatch("publisher-1", 10, Duration.ofSeconds(30), Instant.now(clock));

      assertThat(claimed).hasSize(2);
      OutboxEventRecord record = dao.findById(201L);
      assertThat(record.status()).isEqualTo(OutboxEventStatus.PUBLISHING);
      assertThat(record.version()).isEqualTo(1L);
      assertThat(record.attemptCount()).isZero();
    }

    @Test
    @DisplayName("a second publisher does not reclaim an unexpired PUBLISHING event")
    void noReclaimBeforeExpiry() {
      insert(301L);
      dao.claimBatch("publisher-1", 10, Duration.ofSeconds(30), Instant.now(clock));

      var secondClaim =
          dao.claimBatch("publisher-2", 10, Duration.ofSeconds(30), Instant.now(clock));

      assertThat(secondClaim).isEmpty();
    }

    @Test
    @DisplayName("an expired PUBLISHING claim can be reclaimed")
    void expiredPublishingReclaimed() {
      insert(401L);
      Instant claimedAt = Instant.parse("2026-06-25T12:00:00Z");
      dao.claimBatch("publisher-1", 10, Duration.ofSeconds(30), claimedAt);

      Instant afterExpiry = claimedAt.plus(Duration.ofSeconds(31));
      var reclaimed = dao.claimBatch("publisher-2", 10, Duration.ofSeconds(30), afterExpiry);

      assertThat(reclaimed).hasSize(1);
      assertThat(reclaimed.get(0).status()).isEqualTo(OutboxEventStatus.PUBLISHING);
      assertThat(reclaimed.get(0).version()).isEqualTo(2L);
    }

    @Test
    @DisplayName("a due RETRY_WAIT event is reclaimed")
    void retryWaitReclaimed() {
      insert(501L);
      dao.claimBatch("publisher-1", 10, Duration.ofSeconds(30), Instant.now(clock));
      dao.markRetryWait(
          501L,
          1L,
          EventErrorCode.REDIS_UNAVAILABLE,
          "redis down",
          Instant.parse("2026-06-25T12:00:05Z"),
          Instant.now(clock));

      var reclaimed =
          dao.claimBatch(
              "publisher-2", 10, Duration.ofSeconds(30), Instant.parse("2026-06-25T12:00:06Z"));

      assertThat(reclaimed).hasSize(1);
    }
  }

  @Nested
  @DisplayName("state transitions")
  class StateTransitions {

    @Test
    @DisplayName("markPublished moves a claimed event to PUBLISHED")
    void markPublished() {
      insert(601L);
      var claimed = dao.claimBatch("publisher-1", 10, Duration.ofSeconds(30), Instant.now(clock));
      long version = claimed.get(0).version();

      dao.markPublished(601L, version, Instant.now(clock));

      assertThat(dao.findById(601L).status()).isEqualTo(OutboxEventStatus.PUBLISHED);
    }

    @Test
    @DisplayName("markRetryWait records the failure and schedules the next attempt")
    void markRetryWait() {
      insert(701L);
      var claimed = dao.claimBatch("publisher-1", 10, Duration.ofSeconds(30), Instant.now(clock));
      long version = claimed.get(0).version();

      dao.markRetryWait(
          701L,
          version,
          EventErrorCode.REDIS_UNAVAILABLE,
          "redis down",
          Instant.parse("2026-06-25T12:00:05Z"),
          Instant.now(clock));

      OutboxEventRecord record = dao.findById(701L);
      assertThat(record.status()).isEqualTo(OutboxEventStatus.RETRY_WAIT);
      assertThat(record.attemptCount()).isEqualTo(1);
      assertThat(record.lastErrorCode()).isEqualTo("REDIS_UNAVAILABLE");
      assertThat(record.lastErrorMessage()).isEqualTo("redis down");
    }

    @Test
    @DisplayName("markDead moves an exhausted event to DEAD")
    void markDead() {
      insert(801L);
      var claimed = dao.claimBatch("publisher-1", 10, Duration.ofSeconds(30), Instant.now(clock));
      long version = claimed.get(0).version();

      dao.markDead(801L, version, EventErrorCode.OUTBOX_EXHAUSTED, "exhausted", Instant.now(clock));

      OutboxEventRecord record = dao.findById(801L);
      assertThat(record.status()).isEqualTo(OutboxEventStatus.DEAD);
      assertThat(record.lastErrorCode()).isEqualTo("OUTBOX_EXHAUSTED");
    }

    @Test
    @DisplayName("marking with a stale version throws OutboxCasConflictException")
    void markWithStaleVersionThrows() {
      insert(901L);
      dao.claimBatch("publisher-1", 10, Duration.ofSeconds(30), Instant.now(clock));

      assertThatThrownBy(() -> dao.markPublished(901L, 999L, Instant.now(clock)))
          .isInstanceOf(OutboxCasConflictException.class);
    }
  }
}
