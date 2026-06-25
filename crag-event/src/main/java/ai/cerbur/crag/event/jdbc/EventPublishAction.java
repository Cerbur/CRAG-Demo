package ai.cerbur.crag.event.jdbc;

import ai.cerbur.crag.event.api.EventEnvelope;

/**
 * Delivers a claimed envelope to the transport (for example a Redis Stream).
 *
 * <p>Implementations must be safe to call again: the outbox may reclaim and republish an event
 * after a crash. The returned {@link PublishResult} tells the publisher whether to mark the event
 * published, schedule a retry or declare it dead.
 */
@FunctionalInterface
public interface EventPublishAction {

  PublishResult attempt(EventEnvelope envelope);
}
