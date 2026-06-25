package ai.cerbur.crag.event.jdbc;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Exponential backoff for outbox retry scheduling.
 *
 * <p>{@code backoffFor(n)} doubles the initial delay for each successive attempt and caps it at
 * {@code max}, so a poisoned event backs off without unbounded growth. The clock is injectable so
 * tests can drive retry scheduling deterministically.
 */
public class OutboxBackoffPolicy {

  private final Duration initial;
  private final Duration max;
  private final Clock clock;

  public OutboxBackoffPolicy(Duration initial, Duration max) {
    this(initial, max, Clock.systemUTC());
  }

  public OutboxBackoffPolicy(Duration initial, Duration max, Clock clock) {
    this.initial = Objects.requireNonNull(initial, "initial");
    this.max = Objects.requireNonNull(max, "max");
    this.clock = Objects.requireNonNull(clock, "clock");
    if (initial.isNegative() || initial.isZero()) {
      throw new IllegalArgumentException("initial must be positive");
    }
    if (max.isNegative() || max.compareTo(initial) < 0) {
      throw new IllegalArgumentException("max must be >= initial");
    }
  }

  /**
   * Returns the delay before the {@code nextAttempt}-th publish (1-based). Doubles per attempt,
   * capped at {@code max}; never overflows because each doubling is clamped.
   */
  public Duration backoffFor(int nextAttempt) {
    if (nextAttempt < 1) {
      throw new IllegalArgumentException("nextAttempt must be >= 1");
    }
    Duration result = initial;
    for (int i = 1; i < nextAttempt && result.compareTo(max) < 0; i++) {
      result = result.multipliedBy(2);
      if (result.compareTo(max) > 0) {
        result = max;
      }
    }
    return result.compareTo(max) > 0 ? max : result;
  }

  /** Scheduling instant for the {@code nextAttempt}-th publish. */
  public Instant nextAttemptAt(int nextAttempt) {
    return Instant.now(clock).plus(backoffFor(nextAttempt));
  }
}
