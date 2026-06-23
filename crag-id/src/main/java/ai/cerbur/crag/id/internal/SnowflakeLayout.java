package ai.cerbur.crag.id.internal;

import ai.cerbur.crag.id.api.CragIdParser;
import ai.cerbur.crag.id.api.IdEntityType;
import java.time.Instant;

/**
 * Fixed bit-layout codec for the 64-bit Snowflake ID.
 *
 * <h3>Bit layout</h3>
 *
 * <pre>
 * sign 1 | entity type 8 | timestamp 41 | worker 4 | sequence 10
 * </pre>
 *
 * <p>Entity type codes come from {@link IdEntityType}. Timestamp is relative to {@link
 * #EPOCH_MILLIS}. Worker and sequence are validated against their bit-width limits.
 */
public final class SnowflakeLayout {

  /** 2026-01-01T00:00:00Z in epoch millis. */
  public static final long EPOCH_MILLIS = 1767225600000L;

  private static final int ENTITY_TYPE_SHIFT = 41 + 4 + 10;
  private static final int TIMESTAMP_SHIFT = 4 + 10;
  private static final int WORKER_SHIFT = 10;

  private static final long TIMESTAMP_MASK = (1L << 41) - 1;
  private static final long ENTITY_TYPE_MASK = (1L << 8) - 1;
  private static final int WORKER_MASK = (1 << 4) - 1;
  private static final int SEQUENCE_MASK = (1 << 10) - 1;

  /**
   * Encode the given parts into a single 64-bit Snowflake ID.
   *
   * @throws IllegalArgumentException if any part exceeds its bit-width limit or timestamp is before
   *     {@link #EPOCH_MILLIS}
   */
  public long encode(IdEntityType entityType, long timestampMillis, int workerId, int sequence) {
    if (workerId < 0 || workerId > WORKER_MASK) {
      throw new IllegalArgumentException(
          "Worker ID must be 0–" + WORKER_MASK + ", was " + workerId);
    }
    if (sequence < 0 || sequence > SEQUENCE_MASK) {
      throw new IllegalArgumentException(
          "Sequence must be 0–" + SEQUENCE_MASK + ", was " + sequence);
    }
    if (timestampMillis < EPOCH_MILLIS) {
      throw new IllegalArgumentException(
          "Timestamp " + timestampMillis + " is before epoch " + EPOCH_MILLIS);
    }
    long relative = timestampMillis - EPOCH_MILLIS;
    if (relative > TIMESTAMP_MASK) {
      throw new IllegalArgumentException("Timestamp delta " + relative + " exceeds 41-bit limit");
    }

    return ((long) entityType.code() << ENTITY_TYPE_SHIFT)
        | (relative << TIMESTAMP_SHIFT)
        | ((long) workerId << WORKER_SHIFT)
        | sequence;
  }

  /** Decode a Snowflake ID into its structural parts. */
  public CragIdParser.CragIdParts decode(long id) {
    int entityCode = (int) ((id >> ENTITY_TYPE_SHIFT) & ENTITY_TYPE_MASK);
    IdEntityType entityType = IdEntityType.fromCode(entityCode);

    long relative = (id >> TIMESTAMP_SHIFT) & TIMESTAMP_MASK;
    Instant timestamp = Instant.ofEpochMilli(EPOCH_MILLIS + relative);

    int workerId = (int) ((id >> WORKER_SHIFT) & WORKER_MASK);
    int sequence = (int) (id & SEQUENCE_MASK);

    return new CragIdParser.CragIdParts(entityType, timestamp, workerId, sequence);
  }
}
