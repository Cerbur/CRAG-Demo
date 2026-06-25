package ai.cerbur.crag.event.api;

import java.time.Instant;
import java.util.Objects;
import tools.jackson.databind.ObjectMapper;

/**
 * Stable, domain-agnostic event envelope carried across the Outbox, Redis Streams and consumer
 * boundaries.
 *
 * <p>Numeric Snowflake identifiers ({@code eventId}, {@code resourceId}, {@code operationVersion})
 * are stored as {@code long} internally and exposed as decimal strings at every cross-language
 * boundary, so Redis Stream fields and HTTP payloads never lose integer precision. {@code payload}
 * is a JSON string validated on construction, so malformed payloads never enter the Outbox.
 *
 * <p>The envelope is immutable; invalid input throws {@link IllegalArgumentException} from the
 * compact constructor.
 */
public record EventEnvelope(
    long eventId,
    String eventType,
    String producer,
    String resourceType,
    long resourceId,
    long operationVersion,
    int payloadVersion,
    Instant occurredAt,
    String traceId,
    String payload) {

  private static final ObjectMapper JSON = new ObjectMapper();

  public EventEnvelope {
    requireNonBlank(eventType, "eventType");
    requireNonBlank(producer, "producer");
    requireNonBlank(resourceType, "resourceType");
    requireNonBlank(traceId, "traceId");
    requireNonBlank(payload, "payload");
    Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    if (payloadVersion < 1) {
      throw new IllegalArgumentException("payloadVersion must be >= 1");
    }
    requireValidJson(payload);
  }

  /** Decimal-string form of {@code eventId} for Redis Stream fields and HTTP boundaries. */
  public String eventIdAsString() {
    return Long.toString(eventId);
  }

  /** Decimal-string form of {@code resourceId}. */
  public String resourceIdAsString() {
    return Long.toString(resourceId);
  }

  /** Decimal-string form of {@code operationVersion}. */
  public String operationVersionAsString() {
    return Long.toString(operationVersion);
  }

  private static void requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
  }

  private static void requireValidJson(String payload) {
    try {
      JSON.readTree(payload);
    } catch (Exception e) {
      throw new IllegalArgumentException("payload must be a valid JSON string", e);
    }
  }
}
