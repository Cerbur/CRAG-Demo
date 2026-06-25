package ai.cerbur.crag.event.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("OutboxBackoffPolicy")
class OutboxBackoffPolicyTest {

  private final OutboxBackoffPolicy policy =
      new OutboxBackoffPolicy(Duration.ofSeconds(1), Duration.ofSeconds(30));

  @Nested
  @DisplayName("backoffFor")
  class BackoffFor {

    @Test
    @DisplayName("first attempt uses the initial delay")
    void firstAttemptUsesInitial() {
      assertThat(policy.backoffFor(1)).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("delays double per attempt")
    void delaysDoublePerAttempt() {
      assertThat(policy.backoffFor(2)).isEqualTo(Duration.ofSeconds(2));
      assertThat(policy.backoffFor(3)).isEqualTo(Duration.ofSeconds(4));
      assertThat(policy.backoffFor(4)).isEqualTo(Duration.ofSeconds(8));
    }

    @Test
    @DisplayName("delays are capped at max")
    void cappedAtMax() {
      OutboxBackoffPolicy smallMax =
          new OutboxBackoffPolicy(Duration.ofSeconds(1), Duration.ofSeconds(5));

      assertThat(smallMax.backoffFor(1)).isEqualTo(Duration.ofSeconds(1));
      assertThat(smallMax.backoffFor(2)).isEqualTo(Duration.ofSeconds(2));
      assertThat(smallMax.backoffFor(3)).isEqualTo(Duration.ofSeconds(4));
      assertThat(smallMax.backoffFor(4)).isEqualTo(Duration.ofSeconds(5));
      assertThat(smallMax.backoffFor(100)).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("rejects attempt below 1")
    void rejectsBelowOne() {
      assertThatThrownBy(() -> policy.backoffFor(0)).isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("nextAttemptAt")
  class NextAttemptAt {

    @Test
    @DisplayName("schedules from the injected clock")
    void schedulesFromClock() {
      Clock fixed = Clock.fixed(Instant.parse("2026-06-25T12:00:00Z"), ZoneOffset.UTC);
      OutboxBackoffPolicy clocked =
          new OutboxBackoffPolicy(Duration.ofSeconds(1), Duration.ofSeconds(30), fixed);

      assertThat(clocked.nextAttemptAt(3)).isEqualTo(Instant.parse("2026-06-25T12:00:04Z"));
    }
  }

  @Nested
  @DisplayName("validation")
  class Validation {

    @Test
    @DisplayName("rejects non-positive initial")
    void rejectsNonPositiveInitial() {
      assertThatThrownBy(() -> new OutboxBackoffPolicy(Duration.ZERO, Duration.ofSeconds(1)))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rejects max below initial")
    void rejectsMaxBelowInitial() {
      assertThatThrownBy(
              () -> new OutboxBackoffPolicy(Duration.ofSeconds(5), Duration.ofSeconds(1)))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }
}
