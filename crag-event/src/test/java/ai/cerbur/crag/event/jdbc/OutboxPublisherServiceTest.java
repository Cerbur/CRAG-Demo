package ai.cerbur.crag.event.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import ai.cerbur.crag.event.api.EventErrorCode;
import ai.cerbur.crag.event.api.OutboxEventStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(classes = EventJdbcTestConfiguration.class)
@Transactional
@DisplayName("OutboxPublisherService")
class OutboxPublisherServiceTest {

  @Autowired private JdbcTemplate jdbcTemplate;

  private final Clock clock = Clock.fixed(Instant.parse("2026-06-25T12:00:00Z"), ZoneOffset.UTC);
  private JdbcOutboxEventDao dao;
  private OutboxBackoffPolicy backoff;

  @BeforeEach
  void setUp() {
    dao = new JdbcOutboxEventDao(jdbcTemplate);
    backoff = new OutboxBackoffPolicy(Duration.ofSeconds(1), Duration.ofSeconds(30), clock);
  }

  private OutboxPublisherService service(int maxAttempts) {
    return new OutboxPublisherService(dao, backoff, maxAttempts, Duration.ofSeconds(30), clock);
  }

  private void insert(long eventId) {
    dao.insert(EventTestFixtures.envelope(eventId), Instant.now(clock));
  }

  @Test
  @DisplayName("a successful publish marks the event PUBLISHED")
  void successMarksPublished() {
    insert(101L);
    OutboxPublisherService publisher = service(3);

    var claimed = publisher.claimBatch("publisher-1", 10);
    publisher.publish(claimed.get(0), envelope -> PublishResult.success());

    assertThat(dao.findById(101L).status()).isEqualTo(OutboxEventStatus.PUBLISHED);
  }

  @Test
  @DisplayName("a retryable failure returns the event to RETRY_WAIT below the attempt limit")
  void retryableFailureWaitsForRetry() {
    insert(201L);
    OutboxPublisherService publisher = service(3);

    var claimed = publisher.claimBatch("publisher-1", 10);
    publisher.publish(
        claimed.get(0),
        envelope -> PublishResult.failure(EventErrorCode.REDIS_UNAVAILABLE, "redis down"));

    OutboxEventRecord record = dao.findById(201L);
    assertThat(record.status()).isEqualTo(OutboxEventStatus.RETRY_WAIT);
    assertThat(record.attemptCount()).isEqualTo(1);
    assertThat(record.lastErrorCode()).isEqualTo("REDIS_UNAVAILABLE");
  }

  @Test
  @DisplayName("exhausting attempts moves the event to DEAD with OUTBOX_EXHAUSTED")
  void exhaustionMovesToDead() {
    insert(301L);
    OutboxPublisherService publisher = service(1);

    var claimed = publisher.claimBatch("publisher-1", 10);
    publisher.publish(
        claimed.get(0),
        envelope -> PublishResult.failure(EventErrorCode.REDIS_UNAVAILABLE, "redis down"));

    OutboxEventRecord record = dao.findById(301L);
    assertThat(record.status()).isEqualTo(OutboxEventStatus.DEAD);
    assertThat(record.lastErrorCode()).isEqualTo("OUTBOX_EXHAUSTED");
  }
}
