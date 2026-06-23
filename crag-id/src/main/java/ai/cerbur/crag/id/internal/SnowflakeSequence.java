package ai.cerbur.crag.id.internal;

import ai.cerbur.crag.id.api.IdEntityType;

/**
 * Per-entity-type timestamp/sequence state machine.
 *
 * <p>Tracks the last-used timestamp and sequence counter for one (worker, entity type) combination.
 * On construction the caller must supply a fixed {@code workerId}; the sequence itself does not
 * manage distributed worker coordination — that belongs to the Redis lease layer.
 *
 * <h3>Clock rollback policy</h3>
 *
 * <ul>
 *   <li>Rollback &le; {@code rollbackThresholdMillis}: block until the clock catches up, then
 *       resume with a fresh sequence.
 *   <li>Rollback &gt; threshold: throw {@link ClockRollbackException} — the caller must stop the
 *       generator and surface the failure via readiness.
 * </ul>
 */
public final class SnowflakeSequence {

  private static final int MAX_SEQUENCE = (1 << 10) - 1; // 1023

  private final int workerId;
  private final IdEntityType entityType;
  private final SnowflakeLayout layout;
  private final MonotonicClock clock;
  private final long rollbackThresholdMillis;

  private long lastTimestampMillis;
  private int sequence = -1;

  /**
   * @param workerId fixed worker slot (0–15), assigned by lease layer
   * @param entityType the entity type this sequence serves
   * @param layout Snowflake bit encoder
   * @param clock time source
   * @param rollbackThresholdMillis maximum backward drift tolerated before fail-fast
   */
  public SnowflakeSequence(
      int workerId,
      IdEntityType entityType,
      SnowflakeLayout layout,
      MonotonicClock clock,
      long rollbackThresholdMillis) {
    this.workerId = workerId;
    this.entityType = entityType;
    this.layout = layout;
    this.clock = clock;
    this.rollbackThresholdMillis = rollbackThresholdMillis;
  }

  /**
   * Generate the next ID, handling sequence overflow and clock rollback.
   *
   * @return a new unique Snowflake ID
   * @throws ClockRollbackException if clock rollback exceeds threshold
   */
  public synchronized long nextId() {
    long current = clock.currentTimeMillis();

    if (current < lastTimestampMillis) {
      handleRollback(current);
      // After handling rollback, update current
      current = clock.currentTimeMillis();
    }

    if (current == lastTimestampMillis) {
      sequence++;
      if (sequence > MAX_SEQUENCE) {
        // Sequence exhausted this millisecond — wait for next one
        long nextMs = lastTimestampMillis + 1;
        clock.sleepUntil(nextMs);
        current = nextMs;
        sequence = 0;
      }
    } else {
      // Clock moved forward — reset sequence
      sequence = 0;
    }

    lastTimestampMillis = current;
    return layout.encode(entityType, current, workerId, sequence);
  }

  private void handleRollback(long current) {
    long rollback = lastTimestampMillis - current;
    if (rollback <= rollbackThresholdMillis) {
      // Small rollback — wait until we pass the last timestamp
      long resumeAt = lastTimestampMillis + 1;
      clock.sleepUntil(resumeAt);
      sequence = -1; // will reset to 0 on next iteration
    } else {
      throw new ClockRollbackException(rollback, lastTimestampMillis);
    }
  }
}
