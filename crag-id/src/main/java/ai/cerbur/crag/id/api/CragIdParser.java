package ai.cerbur.crag.id.api;

import java.time.Instant;

/**
 * Public parsing and validation entry-point for Snowflake IDs.
 *
 * <p>Implementations delegate bit-level decoding to {@code SnowflakeLayout} and add decimal-string
 * parsing plus entity-type validation suitable for HTTP/API boundaries.
 */
public interface CragIdParser {

  /** Parse a raw {@code long} ID into its structural parts. */
  CragIdParts parse(long id);

  /**
   * Parse a decimal string representation and verify the embedded entity type matches {@code
   * expectedEntityType}.
   *
   * @throws InvalidCragIdException if the string is not a valid positive long or the embedded
   *     entity type does not match
   */
  long parseDecimal(String value, IdEntityType expectedEntityType);

  /**
   * Assert that {@code id} carries the given {@code expectedEntityType}.
   *
   * @throws InvalidCragIdException if entity type does not match
   */
  void requireEntityType(long id, IdEntityType expectedEntityType);

  /** Decomposed parts of a Snowflake ID. */
  record CragIdParts(IdEntityType entityType, Instant timestamp, int workerId, int sequence) {}
}
