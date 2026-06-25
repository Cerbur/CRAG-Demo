package ai.cerbur.crag.event.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("EventEnvelope")
class EventEnvelopeTest {

  private static final String VALID_PAYLOAD = "{\"message\":\"smoke\"}";

  static EventEnvelope envelopeWith(String payload) {
    return new EventEnvelope(
        123L,
        "EVENT_SMOKE_CREATED",
        "knowledge-service",
        "SMOKE_EVENT",
        456L,
        1L,
        1,
        Instant.parse("2026-06-25T10:00:00Z"),
        "trace-1",
        payload);
  }

  @Nested
  @DisplayName("required fields")
  class RequiredFields {

    @Test
    @DisplayName("constructs when all required fields are present and payload is valid JSON")
    void constructsValidEnvelope() {
      EventEnvelope envelope = envelopeWith(VALID_PAYLOAD);

      assertThat(envelope.eventId()).isEqualTo(123L);
      assertThat(envelope.eventType()).isEqualTo("EVENT_SMOKE_CREATED");
      assertThat(envelope.payload()).isEqualTo(VALID_PAYLOAD);
    }

    @Test
    @DisplayName("rejects blank eventType")
    void rejectsBlankEventType() {
      assertThatThrownBy(
              () ->
                  new EventEnvelope(
                      1L,
                      " ",
                      "knowledge-service",
                      "SMOKE_EVENT",
                      1L,
                      1L,
                      1,
                      Instant.now(),
                      "trace",
                      VALID_PAYLOAD))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rejects blank producer")
    void rejectsBlankProducer() {
      assertThatThrownBy(
              () ->
                  new EventEnvelope(
                      1L,
                      "EVENT_SMOKE_CREATED",
                      "",
                      "SMOKE_EVENT",
                      1L,
                      1L,
                      1,
                      Instant.now(),
                      "trace",
                      VALID_PAYLOAD))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rejects null occurredAt")
    void rejectsNullOccurredAt() {
      assertThatThrownBy(
              () ->
                  new EventEnvelope(
                      1L,
                      "EVENT_SMOKE_CREATED",
                      "knowledge-service",
                      "SMOKE_EVENT",
                      1L,
                      1L,
                      1,
                      null,
                      "trace",
                      VALID_PAYLOAD))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("rejects payloadVersion below 1")
    void rejectsZeroPayloadVersion() {
      assertThatThrownBy(
              () ->
                  new EventEnvelope(
                      1L,
                      "EVENT_SMOKE_CREATED",
                      "knowledge-service",
                      "SMOKE_EVENT",
                      1L,
                      1L,
                      0,
                      Instant.now(),
                      "trace",
                      VALID_PAYLOAD))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("payload validation")
  class PayloadValidation {

    @Test
    @DisplayName("accepts a valid JSON object")
    void acceptsValidJson() {
      EventEnvelope envelope = envelopeWith("{\"runId\":\"r1\",\"n\":3}");

      assertThat(envelope.payload()).contains("r1");
    }

    @Test
    @DisplayName("rejects malformed JSON payload")
    void rejectsMalformedJson() {
      assertThatThrownBy(() -> envelopeWith("{not json"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("payload");
    }

    @Test
    @DisplayName("rejects blank payload")
    void rejectsBlankPayload() {
      assertThatThrownBy(() -> envelopeWith(" ")).isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("decimal string boundaries")
  class DecimalStringBoundaries {

    @Test
    @DisplayName("exposes Snowflake long ids as decimal strings")
    void exposesDecimalStrings() {
      long eventId = 9_000_000_000_000_000_001L;
      long resourceId = 42L;
      long operationVersion = 7L;

      EventEnvelope envelope =
          new EventEnvelope(
              eventId,
              "EVENT_SMOKE_CREATED",
              "knowledge-service",
              "SMOKE_EVENT",
              resourceId,
              operationVersion,
              1,
              Instant.now(),
              "trace",
              VALID_PAYLOAD);

      assertThat(envelope.eventIdAsString()).isEqualTo("9000000000000000001");
      assertThat(envelope.resourceIdAsString()).isEqualTo("42");
      assertThat(envelope.operationVersionAsString()).isEqualTo("7");
    }
  }
}
