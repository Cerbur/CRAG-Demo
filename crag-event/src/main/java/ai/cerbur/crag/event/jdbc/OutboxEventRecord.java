package ai.cerbur.crag.event.jdbc;

import ai.cerbur.crag.event.api.EventEnvelope;
import ai.cerbur.crag.event.api.OutboxEventStatus;
import java.time.Instant;

/**
 * Immutable snapshot of an {@code outbox_event} row consumed by the publisher.
 *
 * <p>{@link #version()} is the post-claim CAS version the publisher must pass back when marking the
 * publish result, so a concurrent reclaim that bumped the version invalidates the mark.
 */
public record OutboxEventRecord(
    long eventId,
    long version,
    String eventType,
    String producer,
    String resourceType,
    long resourceId,
    long operationVersion,
    int payloadVersion,
    Instant occurredAt,
    String traceId,
    String payload,
    OutboxEventStatus status,
    int attemptCount,
    String lastErrorCode,
    String lastErrorMessage) {

  /** Rebuilds the envelope from this row's identity and payload fields. */
  public EventEnvelope toEnvelope() {
    return new EventEnvelope(
        eventId,
        eventType,
        producer,
        resourceType,
        resourceId,
        operationVersion,
        payloadVersion,
        occurredAt,
        traceId,
        payload);
  }
}
