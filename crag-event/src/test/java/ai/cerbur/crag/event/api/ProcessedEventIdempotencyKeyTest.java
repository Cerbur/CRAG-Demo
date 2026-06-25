package ai.cerbur.crag.event.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ProcessedEventIdempotencyKey")
class ProcessedEventIdempotencyKeyTest {

  @Nested
  @DisplayName("stable format")
  class StableFormat {

    @Test
    @DisplayName("formats as eventType:resourceType:resourceId:operationVersion with decimal ids")
    void formatsColonDelimited() {
      ProcessedEventIdempotencyKey key =
          new ProcessedEventIdempotencyKey("EVENT_SMOKE_CREATED", "SMOKE_EVENT", 42L, 7L);

      assertThat(key.format()).isEqualTo("EVENT_SMOKE_CREATED:SMOKE_EVENT:42:7");
      assertThat(key.toString()).isEqualTo("EVENT_SMOKE_CREATED:SMOKE_EVENT:42:7");
    }

    @Test
    @DisplayName("format is stable across numeric magnitudes")
    void formatStableAcrossMagnitudes() {
      ProcessedEventIdempotencyKey key =
          new ProcessedEventIdempotencyKey(
              "DOC_UPLOADED", "DOCUMENT", 9_000_000_000_000_000_001L, 1L);

      assertThat(key.format()).isEqualTo("DOC_UPLOADED:DOCUMENT:9000000000000000001:1");
    }
  }

  @Nested
  @DisplayName("envelope derivation")
  class EnvelopeDerivation {

    @Test
    @DisplayName("from(EventEnvelope) mirrors identity fields")
    void fromEnvelope() {
      EventEnvelope envelope =
          new EventEnvelope(
              100L,
              "EVENT_SMOKE_CREATED",
              "knowledge-service",
              "SMOKE_EVENT",
              42L,
              7L,
              1,
              Instant.parse("2026-06-25T10:00:00Z"),
              "trace-1",
              "{\"message\":\"smoke\"}");

      ProcessedEventIdempotencyKey key = ProcessedEventIdempotencyKey.from(envelope);

      assertThat(key.format()).isEqualTo("EVENT_SMOKE_CREATED:SMOKE_EVENT:42:7");
    }

    @Test
    @DisplayName("two envelopes for the same logical operation produce the same key")
    void sameLogicalOperationProducesSameKey() {
      EventEnvelope first =
          new EventEnvelope(
              1L,
              "EVENT_SMOKE_CREATED",
              "knowledge-service",
              "SMOKE_EVENT",
              42L,
              7L,
              1,
              Instant.now(),
              "trace-a",
              "{\"message\":\"smoke\"}");
      EventEnvelope resent =
          new EventEnvelope(
              2L,
              "EVENT_SMOKE_CREATED",
              "knowledge-service",
              "SMOKE_EVENT",
              42L,
              7L,
              1,
              Instant.now(),
              "trace-b",
              "{\"message\":\"smoke\"}");

      assertThat(ProcessedEventIdempotencyKey.from(first))
          .isEqualTo(ProcessedEventIdempotencyKey.from(resent));
    }
  }

  @Nested
  @DisplayName("validation")
  class Validation {

    @Test
    @DisplayName("rejects blank eventType")
    void rejectsBlankEventType() {
      assertThatThrownBy(() -> new ProcessedEventIdempotencyKey(" ", "SMOKE_EVENT", 1L, 1L))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rejects blank resourceType")
    void rejectsBlankResourceType() {
      assertThatThrownBy(() -> new ProcessedEventIdempotencyKey("EVENT_SMOKE_CREATED", "", 1L, 1L))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }
}
