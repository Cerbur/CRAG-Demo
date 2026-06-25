package ai.cerbur.crag.event.api;

import java.util.Set;

/**
 * Contract implemented by each service to handle consumed events.
 *
 * <p>Implementations MUST be idempotent: the infrastructure delivers each event at least once, so
 * reclaim or restart can invoke {@link #handle(EventEnvelope)} again on the same event. Handlers
 * that call external services must set their own timeouts and stay safe to repeat.
 */
public interface EventHandler {

  /** Consumer name used for the Redis consumer group member and {@code processed_event} rows. */
  String consumerName();

  /** Redis Stream key this handler reads from. */
  String streamKey();

  /** Redis consumer group this handler belongs to. */
  String groupName();

  /** Event types this handler accepts; events whose type is absent are not dispatched to it. */
  Set<String> eventTypes();

  /**
   * Handle a single event. The returned {@link EventHandlerResult} drives whether the message is
   * ACKed, retried or dead-lettered.
   */
  EventHandlerResult handle(EventEnvelope envelope);
}
