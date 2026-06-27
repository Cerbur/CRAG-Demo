package ai.cerbur.crag.event.redis;

import ai.cerbur.crag.event.api.EventEnvelope;
import ai.cerbur.crag.event.api.EventErrorCode;
import ai.cerbur.crag.event.api.ProcessedEventIdempotencyKey;
import ai.cerbur.crag.event.jdbc.JdbcProcessedEventDao;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reclaims idle pending messages and dead-letters those whose delivery count is exhausted.
 *
 * <p>Pending entries idle shorter than {@code claimIdle} are left alone. Idle entries are claimed
 * for this consumer: if their delivery count has reached {@code maxDeliveries} they are
 * dead-lettered and ACKed (so the poison message stops blocking the group); otherwise they are
 * handed back to the {@link MessageProcessor} for re-delivery, where processed_event idempotency
 * keeps the re-run safe.
 */
public class RedisPendingReclaimer {

  private static final Logger log = LoggerFactory.getLogger(RedisPendingReclaimer.class);

  private final RedisStreamOps ops;
  private final RedisStreamEventMapper mapper;
  private final JdbcProcessedEventDao processedDao;
  private final DeadLetterPublisher dlqPublisher;
  private final MessageProcessor processor;
  private final String streamKey;
  private final String groupName;
  private final String consumerName;
  private final Duration claimIdle;
  private final long maxDeliveries;
  private final int batchSize;
  private final Clock clock;

  public RedisPendingReclaimer(
      RedisStreamOps ops,
      RedisStreamEventMapper mapper,
      JdbcProcessedEventDao processedDao,
      DeadLetterPublisher dlqPublisher,
      MessageProcessor processor,
      String streamKey,
      String groupName,
      String consumerName,
      Duration claimIdle,
      long maxDeliveries,
      int batchSize,
      Clock clock) {
    this.ops = ops;
    this.mapper = mapper;
    this.processedDao = processedDao;
    this.dlqPublisher = dlqPublisher;
    this.processor = processor;
    this.streamKey = streamKey;
    this.groupName = groupName;
    this.consumerName = consumerName;
    this.claimIdle = claimIdle;
    this.maxDeliveries = maxDeliveries;
    this.batchSize = batchSize;
    this.clock = clock;
  }

  /** Scans one batch of pending entries and reclaims or dead-letters the idle ones. */
  public void reclaimPending() {
    List<PendingEntry> pending = ops.pending(streamKey, groupName, batchSize);
    for (PendingEntry entry : pending) {
      if (entry.idleMillis() < claimIdle.toMillis()) {
        continue;
      }
      List<StreamEntry> claimed =
          ops.claim(streamKey, groupName, consumerName, claimIdle, List.of(entry.recordId()));
      if (claimed.isEmpty()) {
        continue;
      }
      StreamEntry message = claimed.get(0);
      if (entry.deliveryCount() >= maxDeliveries) {
        deadLetter(message, entry.recordId());
      } else {
        processor.process(message);
      }
    }
  }

  private void deadLetter(StreamEntry message, String recordId) {
    try {
      EventEnvelope envelope = mapper.fromFields(message.fields());
      String idempotencyKey = ProcessedEventIdempotencyKey.from(envelope).format();
      dlqPublisher.publish(envelope, EventErrorCode.HANDLER_FAILED, "delivery exhausted");
      processedDao.markDeadLettered(
          consumerName,
          idempotencyKey,
          EventErrorCode.HANDLER_FAILED,
          "delivery exhausted",
          Instant.now(clock));
    } catch (RuntimeException e) {
      dlqPublisher.publishRaw(
          message.fields(), recordId, EventErrorCode.MESSAGE_MALFORMED, e.getMessage());
    }
    ops.acknowledge(streamKey, groupName, recordId);
  }
}
