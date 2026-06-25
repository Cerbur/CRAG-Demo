package ai.cerbur.crag.event.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.cerbur.crag.event.api.EventEnvelope;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("RedisStreamEventMapper")
class RedisStreamEventMapperTest {

  private final RedisStreamEventMapper mapper = new RedisStreamEventMapper();

  private EventEnvelope sampleEnvelope() {
    return new EventEnvelope(
        9_000_000_000_000_000_001L,
        "EVENT_SMOKE_CREATED",
        "knowledge-service",
        "SMOKE_EVENT",
        42L,
        7L,
        1,
        Instant.parse("2026-06-25T10:00:00Z"),
        "trace-1",
        "{\"message\":\"smoke\"}");
  }

  @Nested
  @DisplayName("toFields")
  class ToFields {

    @Test
    @DisplayName("writes the full envelope with decimal-string boundaries")
    void writesFullEnvelope() {
      Map<String, String> fields = mapper.toFields(sampleEnvelope());

      assertThat(fields.get(RedisStreamEventMapper.FIELD_EVENT_ID))
          .isEqualTo("9000000000000000001");
      assertThat(fields.get(RedisStreamEventMapper.FIELD_EVENT_TYPE))
          .isEqualTo("EVENT_SMOKE_CREATED");
      assertThat(fields.get(RedisStreamEventMapper.FIELD_RESOURCE_ID)).isEqualTo("42");
      assertThat(fields.get(RedisStreamEventMapper.FIELD_OPERATION_VERSION)).isEqualTo("7");
      assertThat(fields.get(RedisStreamEventMapper.FIELD_PAYLOAD_VERSION)).isEqualTo("1");
      assertThat(fields.get(RedisStreamEventMapper.FIELD_OCCURRED_AT))
          .isEqualTo("2026-06-25T10:00:00Z");
      assertThat(fields.get(RedisStreamEventMapper.FIELD_PAYLOAD))
          .isEqualTo("{\"message\":\"smoke\"}");
    }
  }

  @Nested
  @DisplayName("fromFields")
  class FromFields {

    @Test
    @DisplayName("round-trips an envelope through the field map")
    void roundTrips() {
      EventEnvelope original = sampleEnvelope();

      EventEnvelope parsed = mapper.fromFields(mapper.toFields(original));

      assertThat(parsed.eventId()).isEqualTo(original.eventId());
      assertThat(parsed.eventType()).isEqualTo(original.eventType());
      assertThat(parsed.resourceId()).isEqualTo(original.resourceId());
      assertThat(parsed.operationVersion()).isEqualTo(original.operationVersion());
      assertThat(parsed.payload()).isEqualTo(original.payload());
    }

    @Test
    @DisplayName("rejects a missing field")
    void rejectsMissingField() {
      Map<String, String> fields = new LinkedHashMap<>(mapper.toFields(sampleEnvelope()));
      fields.remove(RedisStreamEventMapper.FIELD_EVENT_TYPE);

      assertThatThrownBy(() -> mapper.fromFields(fields))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rejects a non-decimal eventId")
    void rejectsNonDecimalEventId() {
      Map<String, String> fields = new LinkedHashMap<>(mapper.toFields(sampleEnvelope()));
      fields.put(RedisStreamEventMapper.FIELD_EVENT_ID, "not-a-number");

      assertThatThrownBy(() -> mapper.fromFields(fields))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rejects an invalid JSON payload")
    void rejectsInvalidJsonPayload() {
      Map<String, String> fields = new LinkedHashMap<>(mapper.toFields(sampleEnvelope()));
      fields.put(RedisStreamEventMapper.FIELD_PAYLOAD, "{broken");

      assertThatThrownBy(() -> mapper.fromFields(fields))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }
}
