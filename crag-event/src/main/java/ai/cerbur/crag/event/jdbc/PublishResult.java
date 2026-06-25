package ai.cerbur.crag.event.jdbc;

import ai.cerbur.crag.event.api.EventErrorCode;
import java.util.Objects;

/**
 * Result of a single publish attempt, produced by an {@link EventPublishAction}.
 *
 * <p>A failure always carries a stable {@link EventErrorCode} and a short diagnostic message; the
 * publisher records both on the outbox row and decides retry versus dead based on attempt count.
 */
public record PublishResult(Outcome outcome, EventErrorCode errorCode, String errorMessage) {

  public enum Outcome {
    DELIVERED,
    FAILED
  }

  public PublishResult {
    Objects.requireNonNull(outcome, "outcome");
    if (outcome == Outcome.FAILED) {
      Objects.requireNonNull(errorCode, "errorCode");
      if (errorMessage == null || errorMessage.isBlank()) {
        throw new IllegalArgumentException("errorMessage is required for failure");
      }
    }
  }

  public static PublishResult success() {
    return new PublishResult(Outcome.DELIVERED, null, null);
  }

  public static PublishResult failure(EventErrorCode code, String message) {
    return new PublishResult(Outcome.FAILED, code, message);
  }
}
