package ai.cerbur.crag.id.internal;

/**
 * Thrown when the system clock rolls backward beyond the configured threshold.
 *
 * <p>This is a hard-stop signal: the Snowflake sequence cannot safely generate IDs until the clock
 * recovers and the worker re-acquires its lease. The caller (e.g. health indicator) should map this
 * to a readiness DOWN state.
 */
public final class ClockRollbackException extends RuntimeException {

  private final long rollbackMillis;
  private final long lastTimestampMillis;

  public ClockRollbackException(long rollbackMillis, long lastTimestampMillis) {
    super(
        "Clock rolled back "
            + rollbackMillis
            + " ms (last timestamp "
            + lastTimestampMillis
            + "), exceeded threshold");
    this.rollbackMillis = rollbackMillis;
    this.lastTimestampMillis = lastTimestampMillis;
  }

  public long getRollbackMillis() {
    return rollbackMillis;
  }

  public long getLastTimestampMillis() {
    return lastTimestampMillis;
  }
}
