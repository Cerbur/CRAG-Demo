package ai.cerbur.crag.event.redis;

import ai.cerbur.crag.event.api.EventEnvelope;
import ai.cerbur.crag.event.api.EventErrorCode;
import ai.cerbur.crag.event.api.EventHandler;
import ai.cerbur.crag.event.api.EventHandlerResult;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads events from a Redis Stream consumer group and dispatches them to a <strong>naturally
 * idempotent</strong> handler with at-least-once semantics, <em>without</em> any database
 * processed_event table.
 *
 * <p>This is a deliberate exception to the default {@link RedisStreamEventConsumer}, which de-dupes
 * by {@code (consumer, idempotency_key)} in a local {@code processed_event} table. The Ephemeral
 * variant is intended for handlers whose repeated application converges to the same end state (for
 * example Open API Key cache eviction, where re-evicting an already-evicted entry is a no-op) and
 * whose side effect is ephemeral (in-memory cache; empty on restart). Because there is no
 * persistence, duplicate or reclaimed messages are delivered to the handler again on every poll;
 * the handler MUST be safe to invoke multiple times on the same event.
 *
 * <p>Per entry dispatch:
 *
 * <ul>
 *   <li>malformed message → DLQ + ACK (never blocks the queue, never invokes the handler);
 *   <li>successful handler run → ACK;
 *   <li>retryable failure ({@link EventHandlerResult.Status#RETRYABLE_FAILURE} or thrown exception)
 *       → leave pending for reclaim (no ACK, no DLQ);
 *   <li>non-retryable failure ({@link EventHandlerResult.Status#NON_RETRYABLE_FAILURE}) → DLQ +
 *       ACK.
 * </ul>
 *
 * <p>This consumer MUST NOT depend on {@code JdbcProcessedEventDao} or any database; the no-DB
 * contract is part of the design and is enforced by the consumer's constructor signature.
 */
public final class EphemeralRedisStreamConsumer implements MessageProcessor {

  private static final Logger log = LoggerFactory.getLogger(EphemeralRedisStreamConsumer.class);

  private final RedisStreamOps ops;
  private final RedisStreamEventMapper mapper;
  private final DeadLetterPublisher dlqPublisher;
  private final EventHandler handler;
  private final String streamKey;
  private final String groupName;
  private final String consumerName;
  private final int batchSize;

  /**
   * @param ops Redis Stream operations (production {@code StringRedisTemplate}, test in-memory
   *     fake)
   * @param mapper field ↔ envelope mapping
   * @param dlqPublisher dead-letter publisher for malformed / non-retryable entries
   * @param handler naturally-idempotent event handler (no DB dedupe is applied before invocation)
   * @param streamKey Redis Stream key
   * @param groupName consumer group
   * @param consumerName consumer name within the group
   * @param batchSize max entries read per poll
   */
  public EphemeralRedisStreamConsumer(
      RedisStreamOps ops,
      RedisStreamEventMapper mapper,
      DeadLetterPublisher dlqPublisher,
      EventHandler handler,
      String streamKey,
      String groupName,
      String consumerName,
      int batchSize) {
    this.ops = ops;
    this.mapper = mapper;
    this.dlqPublisher = dlqPublisher;
    this.handler = handler;
    this.streamKey = streamKey;
    this.groupName = groupName;
    this.consumerName = consumerName;
    this.batchSize = batchSize;
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

    EventHandlerResult result;
    try {
      result = handler.handle(envelope);
    } catch (RuntimeException e) {
      // handler 抛异常视为可重试，留 pending 等待 reclaim
      log.warn(
          "Ephemeral handler threw on stream={} record={} — leaving pending for reclaim",
          streamKey,
          entry.recordId(),
          e);
      return;
    }

    switch (result.outcome()) {
      case COMPLETE -> ops.acknowledge(streamKey, groupName, entry.recordId());
      case RETRY -> {
        // 留 pending，等待 RedisPendingReclaimer 重领（reclaim 模式见 EventAutoConfiguration）
        log.debug(
            "Ephemeral handler retryable failure on stream={} record={} — leaving pending",
            streamKey,
            entry.recordId());
      }
      case DEAD_LETTER -> {
        dlqPublisher.publish(envelope, EventErrorCode.HANDLER_NON_RETRYABLE, result.message());
        ops.acknowledge(streamKey, groupName, entry.recordId());
      }
    }
  }
}
