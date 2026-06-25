package ai.cerbur.crag.event.redis;

/**
 * Processes a single stream entry, used by the reclaimer to re-deliver reclaimed messages.
 *
 * <p>Implementations (the consumer) must be idempotent: a reclaimed message may already have a
 * {@code processed_event} row that short-circuits the handler.
 */
@FunctionalInterface
public interface MessageProcessor {

  void process(StreamEntry entry);
}
