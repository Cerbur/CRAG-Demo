package ai.cerbur.crag.id.internal;

/**
 * Testable clock abstraction so Snowflake core logic never calls {@code System.currentTimeMillis()}
 * directly.
 */
public interface MonotonicClock {

  /**
   * Current time in epoch milliseconds. Must be monotonically non-decreasing under normal
   * operation.
   */
  long currentTimeMillis();

  /** Block the calling thread until the given epoch millis is reached. */
  void sleepUntil(long epochMillis);
}
