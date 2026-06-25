package ai.cerbur.crag.knowledge.smoke.event;

import static org.assertj.core.api.Assertions.assertThat;

import ai.cerbur.crag.event.api.EventEnvelope;
import ai.cerbur.crag.event.api.EventHandlerResult;
import ai.cerbur.crag.knowledge.smoke.dto.KnowledgeSmokeFailMode;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@DisplayName("KnowledgeSmokeEventHandler")
class KnowledgeSmokeEventHandlerTest {

  private final KnowledgeSmokeEventHandler handler =
      new KnowledgeSmokeEventHandler(
          new ObjectMapper(), "crag:event:knowledge", "knowledge-smoke", "knowledge-smoke-1");

  private EventEnvelope envelope(KnowledgeSmokeFailMode failMode) {
    String payload =
        "{\"runId\":\"run-1\",\"message\":\"hi\",\"failMode\":\"" + failMode.name() + "\"}";
    return new EventEnvelope(
        1L,
        "EVENT_SMOKE_CREATED",
        "knowledge-service",
        "SMOKE_EVENT",
        1L,
        1L,
        1,
        Instant.parse("2026-06-25T10:00:00Z"),
        "run-1",
        payload);
  }

  @Test
  @DisplayName("failMode NONE completes successfully")
  void noneSucceeds() {
    EventHandlerResult result = handler.handle(envelope(KnowledgeSmokeFailMode.NONE));

    assertThat(result.outcome()).isEqualTo(EventHandlerResult.Outcome.COMPLETE);
  }

  @Test
  @DisplayName("failMode ALWAYS returns a retryable failure")
  void alwaysIsRetryable() {
    EventHandlerResult result = handler.handle(envelope(KnowledgeSmokeFailMode.ALWAYS));

    assertThat(result.outcome()).isEqualTo(EventHandlerResult.Outcome.RETRY);
  }

  @Test
  @DisplayName("failMode NON_RETRYABLE returns a non-retryable failure")
  void nonRetryableIsDeadLetter() {
    EventHandlerResult result = handler.handle(envelope(KnowledgeSmokeFailMode.NON_RETRYABLE));

    assertThat(result.outcome()).isEqualTo(EventHandlerResult.Outcome.DEAD_LETTER);
  }

  @Test
  @DisplayName("defaults to NONE when the payload omits failMode")
  void defaultsToNoneWhenAbsent() {
    EventEnvelope envelope =
        new EventEnvelope(
            1L,
            "EVENT_SMOKE_CREATED",
            "knowledge-service",
            "SMOKE_EVENT",
            1L,
            1L,
            1,
            Instant.parse("2026-06-25T10:00:00Z"),
            "run-1",
            "{\"runId\":\"run-1\"}");

    EventHandlerResult result = handler.handle(envelope);

    assertThat(result.outcome()).isEqualTo(EventHandlerResult.Outcome.COMPLETE);
  }
}
