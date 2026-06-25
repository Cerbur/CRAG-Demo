package ai.cerbur.crag.event.jdbc;

import ai.cerbur.crag.event.api.EventEnvelope;
import java.time.Instant;

/** Shared builders for crag-event JDBC component tests. */
final class EventTestFixtures {

  private static final String PAYLOAD = "{\"message\":\"smoke\"}";

  private EventTestFixtures() {}

  static EventEnvelope envelope(long eventId) {
    return new EventEnvelope(
        eventId,
        "EVENT_SMOKE_CREATED",
        "knowledge-service",
        "SMOKE_EVENT",
        1L,
        1L,
        1,
        Instant.parse("2026-06-25T10:00:00Z"),
        "trace-" + eventId,
        PAYLOAD);
  }

  static Instant now() {
    return Instant.parse("2026-06-25T12:00:00Z");
  }
}
