package ai.cerbur.crag.event.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("EventHandlerResult")
class EventHandlerResultTest {

  @Nested
  @DisplayName("factories")
  class Factories {

    @Test
    @DisplayName("success has SUCCESS status and null message")
    void success() {
      EventHandlerResult result = EventHandlerResult.success();

      assertThat(result.status()).isEqualTo(EventHandlerResult.Status.SUCCESS);
      assertThat(result.message()).isNull();
    }

    @Test
    @DisplayName("retryableFailure carries the provided message")
    void retryableFailure() {
      EventHandlerResult result = EventHandlerResult.retryableFailure("sidecar timeout");

      assertThat(result.status()).isEqualTo(EventHandlerResult.Status.RETRYABLE_FAILURE);
      assertThat(result.message()).isEqualTo("sidecar timeout");
    }

    @Test
    @DisplayName("nonRetryableFailure carries the provided message")
    void nonRetryableFailure() {
      EventHandlerResult result = EventHandlerResult.nonRetryableFailure("schema mismatch");

      assertThat(result.status()).isEqualTo(EventHandlerResult.Status.NON_RETRYABLE_FAILURE);
      assertThat(result.message()).isEqualTo("schema mismatch");
    }
  }

  @Nested
  @DisplayName("outcome mapping")
  class OutcomeMapping {

    @Test
    @DisplayName("success maps to COMPLETE")
    void successComplete() {
      assertThat(EventHandlerResult.success().outcome())
          .isEqualTo(EventHandlerResult.Outcome.COMPLETE);
    }

    @Test
    @DisplayName("retryable failure maps to RETRY")
    void retryableMapsRetry() {
      assertThat(EventHandlerResult.retryableFailure("boom").outcome())
          .isEqualTo(EventHandlerResult.Outcome.RETRY);
    }

    @Test
    @DisplayName("non-retryable failure maps to DEAD_LETTER")
    void nonRetryableMapsDeadLetter() {
      assertThat(EventHandlerResult.nonRetryableFailure("fatal").outcome())
          .isEqualTo(EventHandlerResult.Outcome.DEAD_LETTER);
    }
  }

  @Nested
  @DisplayName("validation")
  class Validation {

    @Test
    @DisplayName("retryableFailure requires a non-blank message")
    void retryableRequiresMessage() {
      assertThatThrownBy(() -> EventHandlerResult.retryableFailure(" "))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("nonRetryableFailure requires a non-blank message")
    void nonRetryableRequiresMessage() {
      assertThatThrownBy(() -> EventHandlerResult.nonRetryableFailure(null))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }
}
