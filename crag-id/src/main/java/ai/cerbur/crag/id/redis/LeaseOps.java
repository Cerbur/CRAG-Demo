package ai.cerbur.crag.id.redis;

/**
 * Package-private abstraction over low-level Redis key operations needed for worker lease.
 *
 * <p>Two implementations exist: the production {@code RedisTemplateLeaseOps} backed by {@code
 * StringRedisTemplate} with Lua scripts for atomic compare-and-swap, and the test-only {@code
 * FakeRedisTemplate} backed by an in-memory map.
 */
interface LeaseOps {

  /** SET NX with absolute TTL in millis. Returns {@code true} if the key was created. */
  boolean setIfAbsent(String key, String value, long ttlMillis);

  /** GET that respects expiry. Returns {@code null} if key is absent or expired. */
  String get(String key);

  /**
   * Compare-and-delete: remove the key only if its current value equals {@code expectedValue}.
   * Returns {@code true} if deletion occurred.
   */
  boolean compareAndDelete(String key, String expectedValue);

  /**
   * Compare-and-set with TTL: if the key exists and its current value equals {@code expectedValue},
   * overwrite it with {@code newValue} and reset the TTL. Returns {@code true} if the update
   * occurred.
   */
  boolean compareAndSet(String key, String expectedValue, String newValue, long ttlMillis);
}
