package ai.cerbur.crag.event.redis;

import ai.cerbur.crag.event.api.EventEnvelope;
import ai.cerbur.crag.event.api.EventErrorCode;
import ai.cerbur.crag.event.api.EventHandler;
import ai.cerbur.crag.event.api.ProcessedEventIdempotencyKey;
import ai.cerbur.crag.event.api.ProcessedEventStatus;
import ai.cerbur.crag.event.jdbc.JdbcProcessedEventDao;
import ai.cerbur.crag.event.jdbc.ProcessedEventRecord;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads events from a Redis Stream consumer group and dispatches them to a handler with
 * at-least-once and idempotent semantics.
 *
 * <p>Per entry: a malformed message is dead-lettered and ACKed so it never blocks the queue; a
 * duplicate of an already-terminal event is ACKed without re-running the handler; a successful
 * handler run marks the event processed and ACKs; a retryable failure records the failure and
 * leaves the message pending for reclaim; a non-retryable failure is dead-lettered, marked and
 * ACKed.
 */
public class RedisStreamEventConsumer implements MessageProcessor {

  private static final Logger log = LoggerFactory.getLogger(RedisStreamEventConsumer.class);

  private final RedisStreamOps ops;
  private final RedisStreamEventMapper mapper;
  private final JdbcProcessedEventDao processedDao;
  private final DeadLetterPublisher dlqPublisher;
  private final EventHandler handler;
  private final String streamKey;
  private final String groupName;
  private final String consumerName;
  private final int batchSize;
  private final Clock clock;

  public RedisStreamEventConsumer(
      RedisStreamOps ops,
      RedisStreamEventMapper mapper,
      JdbcProcessedEventDao processedDao,
      DeadLetterPublisher dlqPublisher,
      EventHandler handler,
      String streamKey,
      String groupName,
      String consumerName,
      int batchSize,
      Clock clock) {
    this.ops = ops;
    this.mapper = mapper;
    this.processedDao = processedDao;
    this.dlqPublisher = dlqPublisher;
    this.handler = handler;
    this.streamKey = streamKey;
    this.groupName = groupName;
    this.consumerName = consumerName;
    this.batchSize = batchSize;
    this.clock = clock;
  }

  /** Ensures the group exists and processes one batch of never-delivered messages. */
  public void processNextBatch() {
    ops.ensureGroup(streamKey, groupName);
    List<StreamEntry> entries = ops.readNewInGroup(streamKey, groupName, consumerName, batchSize);
    for (StreamEntry entry : entries) {
      process(entry);
    }
  }

  @Override
  public void process(StreamEntry entry) {
    EventEnvelope envelope;
    try {
      envelope = mapper.fromFields(entry.fields());
    } catch (RuntimeException e) {
      dlqPublisher.publishRaw(
          entry.fields(), entry.recordId(), EventErrorCode.MESSAGE_MALFORMED, e.getMessage());
      ops.acknowledge(streamKey, groupName, entry.recordId());
      return;
    }

    String idempotencyKey = ProcessedEventIdempotencyKey.from(envelope).format();
    boolean fresh =
        processedDao.insertPlaceholder(
            consumerName,
            envelope,
            idempotencyKey,
            streamKey,
            entry.recordId(),
            Instant.now(clock));
    if (!fresh) {
      ProcessedEventRecord existing =
          processedDao.findByIdempotencyKey(consumerName, idempotencyKey);
      if (existing != null && existing.status() != ProcessedEventStatus.FAILED) {
        ops.acknowledge(streamKey, groupName, entry.recordId());
        return;
      }
    }

    var result = handler.handle(envelope);
    Instant now = Instant.now(clock);
    switch (result.outcome()) {
      case COMPLETE -> {
        processedDao.markProcessed(consumerName, idempotencyKey, entry.recordId(), now);
        ops.acknowledge(streamKey, groupName, entry.recordId());
      }
      case RETRY ->
          processedDao.markFailed(
              consumerName, idempotencyKey, EventErrorCode.HANDLER_FAILED, result.message(), now);
      case DEAD_LETTER -> {
        dlqPublisher.publish(envelope, EventErrorCode.HANDLER_NON_RETRYABLE, result.message());
        processedDao.markDeadLettered(
            consumerName,
            idempotencyKey,
            EventErrorCode.HANDLER_NON_RETRYABLE,
            result.message(),
            now);
        ops.acknowledge(streamKey, groupName, entry.recordId());
      }
    }
  }
}
