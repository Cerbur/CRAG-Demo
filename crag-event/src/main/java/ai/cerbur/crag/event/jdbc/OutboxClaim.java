package ai.cerbur.crag.event.jdbc;

import ai.cerbur.crag.event.api.EventEnvelope;

/**
 * A successfully claimed outbox event, ready for the publisher to deliver and then mark.
 *
 * <p>Wraps the post-claim {@link OutboxEventRecord} so callers carry the CAS version alongside the
 * envelope through the publish and mark steps.
 */
public record OutboxClaim(OutboxEventRecord record) {

  public long eventId() {
    return record.eventId();
  }

  public long version() {
    return record.version();
  }

  public EventEnvelope envelope() {
    return record.toEnvelope();
  }
}
