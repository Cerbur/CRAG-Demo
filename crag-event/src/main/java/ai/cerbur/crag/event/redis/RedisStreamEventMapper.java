package ai.cerbur.crag.event.redis;

import ai.cerbur.crag.event.api.EventEnvelope;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps {@link EventEnvelope} to and from the field-based Redis Stream entry layout.
 *
 * <p>Numeric Snowflake segments use decimal strings at the boundary; {@code occurredAt} uses
 * ISO-8601. {@link #fromFields(Map)} throws {@link IllegalArgumentException} when a field is
 * missing, blank or unparseable, or when the payload is invalid JSON; the consumer treats that as a
 * malformed message and dead-letters it.
 */
public class RedisStreamEventMapper {

  public static final String FIELD_EVENT_ID = "eventId";
  public static final String FIELD_EVENT_TYPE = "eventType";
  public static final String FIELD_PRODUCER = "producer";
  public static final String FIELD_RESOURCE_TYPE = "resourceType";
  public static final String FIELD_RESOURCE_ID = "resourceId";
  public static final String FIELD_OPERATION_VERSION = "operationVersion";
  public static final String FIELD_OCCURRED_AT = "occurredAt";
  public static final String FIELD_TRACE_ID = "traceId";
  public static final String FIELD_PAYLOAD_VERSION = "payloadVersion";
  public static final String FIELD_PAYLOAD = "payload";

  /** Serializes an envelope to the field map written to the stream. */
  public Map<String, String> toFields(EventEnvelope envelope) {
    Map<String, String> fields = new LinkedHashMap<>();
    fields.put(FIELD_EVENT_ID, envelope.eventIdAsString());
    fields.put(FIELD_EVENT_TYPE, envelope.eventType());
    fields.put(FIELD_PRODUCER, envelope.producer());
    fields.put(FIELD_RESOURCE_TYPE, envelope.resourceType());
    fields.put(FIELD_RESOURCE_ID, envelope.resourceIdAsString());
    fields.put(FIELD_OPERATION_VERSION, envelope.operationVersionAsString());
    fields.put(FIELD_OCCURRED_AT, envelope.occurredAt().toString());
    fields.put(FIELD_TRACE_ID, envelope.traceId());
    fields.put(FIELD_PAYLOAD_VERSION, Integer.toString(envelope.payloadVersion()));
    fields.put(FIELD_PAYLOAD, envelope.payload());
    return fields;
  }

  /** Parses a field map back to an envelope, throwing on any missing or invalid field. */
  public EventEnvelope fromFields(Map<String, String> fields) {
    long eventId = parseLong(fields, FIELD_EVENT_ID);
    long resourceId = parseLong(fields, FIELD_RESOURCE_ID);
    long operationVersion = parseLong(fields, FIELD_OPERATION_VERSION);
    int payloadVersion = parseInt(fields, FIELD_PAYLOAD_VERSION);
    Instant occurredAt = parseInstant(fields, FIELD_OCCURRED_AT);
    return new EventEnvelope(
        eventId,
        require(fields, FIELD_EVENT_TYPE),
        require(fields, FIELD_PRODUCER),
        require(fields, FIELD_RESOURCE_TYPE),
        resourceId,
        operationVersion,
        payloadVersion,
        occurredAt,
        require(fields, FIELD_TRACE_ID),
        require(fields, FIELD_PAYLOAD));
  }

  private static String require(Map<String, String> fields, String name) {
    String value = fields.get(name);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("missing or blank stream field: " + name);
    }
    return value;
  }

  private static long parseLong(Map<String, String> fields, String name) {
    String value = require(fields, name);
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(
          "stream field " + name + " is not a decimal long: " + value);
    }
  }

  private static int parseInt(Map<String, String> fields, String name) {
    String value = require(fields, name);
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("stream field " + name + " is not an int: " + value);
    }
  }

  private static Instant parseInstant(Map<String, String> fields, String name) {
    String value = require(fields, name);
    try {
      return Instant.parse(value);
    } catch (Exception e) {
      throw new IllegalArgumentException(
          "stream field " + name + " is not an ISO-8601 instant: " + value);
    }
  }
}
