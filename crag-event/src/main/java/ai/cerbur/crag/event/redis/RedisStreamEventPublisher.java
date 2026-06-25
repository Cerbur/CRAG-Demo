package ai.cerbur.crag.event.redis;

import ai.cerbur.crag.event.api.EventEnvelope;
import ai.cerbur.crag.event.api.EventErrorCode;
import ai.cerbur.crag.event.jdbc.EventPublishAction;
import ai.cerbur.crag.event.jdbc.PublishResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Publishes events to a Redis Stream, adapting the transport to the outbox's {@link
 * EventPublishAction}.
 *
 * <p>Any failure to reach Redis is reported as a retryable {@link EventErrorCode#REDIS_UNAVAILABLE}
 * result so the outbox schedules a retry; it never marks the event published on a write failure.
 */
public class RedisStreamEventPublisher implements EventPublishAction {

  private static final Logger log = LoggerFactory.getLogger(RedisStreamEventPublisher.class);

  private final RedisStreamOps ops;
  private final RedisStreamEventMapper mapper;
  private final String streamKey;

  public RedisStreamEventPublisher(
      RedisStreamOps ops, RedisStreamEventMapper mapper, String streamKey) {
    this.ops = ops;
    this.mapper = mapper;
    this.streamKey = streamKey;
  }

  @Override
  public PublishResult attempt(EventEnvelope envelope) {
    try {
      ops.add(streamKey, mapper.toFields(envelope));
      return PublishResult.success();
    } catch (RuntimeException e) {
      log.debug(
          "Redis Stream publish failed for eventId={}: {}", envelope.eventId(), e.getMessage());
      return PublishResult.failure(EventErrorCode.REDIS_UNAVAILABLE, "redis stream publish failed");
    }
  }
}
