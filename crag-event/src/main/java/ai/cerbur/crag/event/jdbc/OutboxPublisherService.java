package ai.cerbur.crag.event.jdbc;

import ai.cerbur.crag.event.api.EventErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestrates one publisher pass: claim a batch, deliver each event via an {@link
 * EventPublishAction}, then mark the outbox result.
 *
 * <p>On a publish failure the event returns to {@code RETRY_WAIT} with a backoff-scheduled next
 * attempt; once attempts reach {@code maxAttempts} it moves to {@code DEAD}. A version-CAS loss on
 * the mark (another publisher reclaimed the row) is benign and logged at debug. This class owns no
 * transport: the Redis wiring is plugged in by a later task through {@link EventPublishAction}.
 */
public class OutboxPublisherService {

  private static final Logger log = LoggerFactory.getLogger(OutboxPublisherService.class);

  private final JdbcOutboxEventDao dao;
  private final OutboxBackoffPolicy backoff;
  private final int maxAttempts;
  private final Duration claimDuration;
  private final Clock clock;

  public OutboxPublisherService(
      JdbcOutboxEventDao dao,
      OutboxBackoffPolicy backoff,
      int maxAttempts,
      Duration claimDuration) {
    this(dao, backoff, maxAttempts, claimDuration, Clock.systemUTC());
  }

  public OutboxPublisherService(
      JdbcOutboxEventDao dao,
      OutboxBackoffPolicy backoff,
      int maxAttempts,
      Duration claimDuration,
      Clock clock) {
    if (maxAttempts < 1) {
      throw new IllegalArgumentException("maxAttempts must be >= 1");
    }
    this.dao = dao;
    this.backoff = backoff;
    this.maxAttempts = maxAttempts;
    this.claimDuration = claimDuration;
    this.clock = clock;
  }

  /** Claims up to {@code batchSize} due events for {@code publisher}. */
  public List<OutboxClaim> claimBatch(String publisher, int batchSize) {
    return dao.claimBatch(publisher, batchSize, claimDuration, Instant.now(clock)).stream()
        .map(OutboxClaim::new)
        .toList();
  }

  /** Delivers a claimed event and marks the outbox row published, retried or dead. */
  public void publish(OutboxClaim claim, EventPublishAction action) {
    OutboxEventRecord record = claim.record();
    PublishResult result = action.attempt(claim.envelope());
    Instant now = Instant.now(clock);
    try {
      if (result.outcome() == PublishResult.Outcome.DELIVERED) {
        dao.markPublished(record.eventId(), record.version(), now);
        return;
      }
      int nextAttempt = record.attemptCount() + 1;
      if (nextAttempt >= maxAttempts) {
        dao.markDead(
            record.eventId(),
            record.version(),
            EventErrorCode.OUTBOX_EXHAUSTED,
            result.errorMessage(),
            now);
      } else {
        dao.markRetryWait(
            record.eventId(),
            record.version(),
            result.errorCode(),
            result.errorMessage(),
            backoff.nextAttemptAt(nextAttempt),
            now);
      }
    } catch (OutboxCasConflictException e) {
      log.debug(
          "Outbox CAS conflict for eventId={} (reclaimed by another publisher): {}",
          record.eventId(),
          e.getMessage());
    }
  }
}
