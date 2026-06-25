package ai.cerbur.crag.event.api;

import java.util.Objects;

/**
 * Stable idempotency key for processed events.
 *
 * <p>The default format is {@code eventType:resourceType:resourceId:operationVersion}, using
 * decimal forms for the numeric segments. It lets the same logical operation be re-sent with a new
 * {@code eventId} (for example after a producer restart) while the consumer still de-duplicates it.
 */
public record ProcessedEventIdempotencyKey(
    String eventType, String resourceType, long resourceId, long operationVersion) {

  public ProcessedEventIdempotencyKey {
    requireNonBlank(eventType, "eventType");
    requireNonBlank(resourceType, "resourceType");
  }

  /** Builds the key from an envelope's identity fields. */
  public static ProcessedEventIdempotencyKey from(EventEnvelope envelope) {
    Objects.requireNonNull(envelope, "envelope");
    return new ProcessedEventIdempotencyKey(
        envelope.eventType(),
        envelope.resourceType(),
        envelope.resourceId(),
        envelope.operationVersion());
  }

  /** Formats the key using the stable colon-delimited layout. */
  public String format() {
    return eventType + ":" + resourceType + ":" + resourceId + ":" + operationVersion;
  }

  @Override
  public String toString() {
    return format();
  }

  private static void requireNonBlank(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
  }
}
