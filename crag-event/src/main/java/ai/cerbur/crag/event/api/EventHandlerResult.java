package ai.cerbur.crag.event.api;

import java.util.Objects;

/**
 * Outcome of handling one event, mapped by the infrastructure to an ACK / retry / dead-letter
 * decision.
 *
 * <p>{@link #outcome()} gives the pure, delivery-count-independent decision. The consumer
 * additionally promotes an exhausted retry to a dead letter once the delivery count reaches the
 * configured {@code maxDeliveries}.
 */
public record EventHandlerResult(Status status, String message) {

  public enum Status {
    SUCCESS,
    RETRYABLE_FAILURE,
    NON_RETRYABLE_FAILURE
  }

  /** Infrastructure decision derived from {@link Status}. */
  public enum Outcome {
    COMPLETE,
    RETRY,
    DEAD_LETTER
  }

  public EventHandlerResult {
    Objects.requireNonNull(status, "status");
    if (status != Status.SUCCESS && (message == null || message.isBlank())) {
      throw new IllegalArgumentException("message is required for failure results");
    }
  }

  public static EventHandlerResult success() {
    return new EventHandlerResult(Status.SUCCESS, null);
  }

  public static EventHandlerResult retryableFailure(String message) {
    return new EventHandlerResult(Status.RETRYABLE_FAILURE, message);
  }

  public static EventHandlerResult nonRetryableFailure(String message) {
    return new EventHandlerResult(Status.NON_RETRYABLE_FAILURE, message);
  }

  public Outcome outcome() {
    return switch (status) {
      case SUCCESS -> Outcome.COMPLETE;
      case RETRYABLE_FAILURE -> Outcome.RETRY;
      case NON_RETRYABLE_FAILURE -> Outcome.DEAD_LETTER;
    };
  }
}
