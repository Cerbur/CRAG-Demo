package ai.cerbur.crag.event.redis;

import static org.assertj.core.api.Assertions.assertThat;

import ai.cerbur.crag.event.api.EventEnvelope;
import ai.cerbur.crag.event.api.EventErrorCode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DeadLetterPublisher")
class DeadLetterPublisherTest {

  private static final String DLQ_KEY = "crag:event:knowledge:dlq";

  private FakeRedisStreamOps ops;
  private DeadLetterPublisher dlq;

  private EventEnvelope envelope() {
    return new EventEnvelope(
        1L,
        "EVENT_SMOKE_CREATED",
        "knowledge-service",
        "SMOKE_EVENT",
        1L,
        1L,
        1,
        Instant.parse("2026-06-25T10:00:00Z"),
        "trace-1",
        "{\"message\":\"smoke\"}");
  }

  @BeforeEach
  void setUp() {
    ops = new FakeRedisStreamOps();
    dlq = new DeadLetterPublisher(ops, new RedisStreamEventMapper(), DLQ_KEY);
  }

  @Test
  @DisplayName("publish writes the envelope plus error fields to the DLQ stream")
  void publishWritesEnvelopeWithError() {
    dlq.publish(envelope(), EventErrorCode.HANDLER_NON_RETRYABLE, "handler rejected");

    Map<String, String> entry = ops.stream(DLQ_KEY).get(0).fields();
    assertThat(entry.get(RedisStreamEventMapper.FIELD_EVENT_ID)).isEqualTo("1");
    assertThat(entry.get(DeadLetterPublisher.FIELD_ERROR_CODE)).isEqualTo("HANDLER_NON_RETRYABLE");
    assertThat(entry.get(DeadLetterPublisher.FIELD_ERROR_MESSAGE)).isEqualTo("handler rejected");
  }

  @Test
  @DisplayName("publishRaw preserves raw fields and tags the original record id")
  void publishRawPreservesRawFields() {
    Map<String, String> raw = new LinkedHashMap<>();
    raw.put("eventId", "1");
    raw.put("garbage", "yes");

    dlq.publishRaw(raw, "1234-0", EventErrorCode.MESSAGE_MALFORMED, "missing fields");

    Map<String, String> entry = ops.stream(DLQ_KEY).get(0).fields();
    assertThat(entry.get("garbage")).isEqualTo("yes");
    assertThat(entry.get(DeadLetterPublisher.FIELD_ORIGINAL_RECORD_ID)).isEqualTo("1234-0");
    assertThat(entry.get(DeadLetterPublisher.FIELD_ERROR_CODE)).isEqualTo("MESSAGE_MALFORMED");
  }
}
