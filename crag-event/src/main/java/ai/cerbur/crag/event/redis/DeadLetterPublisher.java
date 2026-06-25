package ai.cerbur.crag.event.redis;

import ai.cerbur.crag.event.api.EventEnvelope;
import ai.cerbur.crag.event.api.EventErrorCode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Writes failed messages to the dead-letter stream.
 *
 * <p>For a well-formed envelope the DLQ entry carries the full envelope fields plus {@code
 * errorCode} and {@code errorMessage}; for a malformed entry (which cannot be parsed into an
 * envelope) it preserves the raw fields and the original record id so the failure stays
 * diagnosable.
 */
public class DeadLetterPublisher {

  /** DLQ field for the stable error code. */
  public static final String FIELD_ERROR_CODE = "errorCode";

  /** DLQ field for a short, safe error summary. */
  public static final String FIELD_ERROR_MESSAGE = "errorMessage";

  /** DLQ field recording the source record id when the entry could not be parsed. */
  public static final String FIELD_ORIGINAL_RECORD_ID = "originalRecordId";

  private static final int MAX_MESSAGE_LENGTH = 500;

  private final RedisStreamOps ops;
  private final RedisStreamEventMapper mapper;
  private final String dlqStreamKey;

  public DeadLetterPublisher(
      RedisStreamOps ops, RedisStreamEventMapper mapper, String dlqStreamKey) {
    this.ops = ops;
    this.mapper = mapper;
    this.dlqStreamKey = dlqStreamKey;
  }

  /** Dead-letters a well-formed envelope with the given failure cause. */
  public String publish(EventEnvelope envelope, EventErrorCode code, String message) {
    Map<String, String> fields = new LinkedHashMap<>(mapper.toFields(envelope));
    fields.put(FIELD_ERROR_CODE, code.name());
    fields.put(FIELD_ERROR_MESSAGE, truncate(message));
    return ops.add(dlqStreamKey, fields);
  }

  /** Dead-letters raw fields (unparseable entry), tagging the originating record id. */
  public String publishRaw(
      Map<String, String> rawFields, String originalRecordId, EventErrorCode code, String message) {
    Map<String, String> fields = new LinkedHashMap<>(rawFields);
    fields.put(FIELD_ORIGINAL_RECORD_ID, originalRecordId);
    fields.put(FIELD_ERROR_CODE, code.name());
    fields.put(FIELD_ERROR_MESSAGE, truncate(message));
    return ops.add(dlqStreamKey, fields);
  }

  private static String truncate(String message) {
    if (message == null) {
      return "";
    }
    return message.length() <= MAX_MESSAGE_LENGTH
        ? message
        : message.substring(0, MAX_MESSAGE_LENGTH);
  }
}
