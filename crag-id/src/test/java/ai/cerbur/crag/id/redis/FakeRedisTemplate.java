package ai.cerbur.crag.id.redis;

/**
 * Test adapter that wraps {@link FakeRedisMap} and implements {@link LeaseOps}.
 *
 * <p>Used by {@link RedisWorkerLeaseRepositoryTest} so the repository can be exercised without a
 * real Redis connection.
 */
final class FakeRedisTemplate implements LeaseOps {

  private final FakeRedisMap map;

  FakeRedisTemplate(FakeRedisMap map) {
    this.map = map;
  }

  @Override
  public boolean setIfAbsent(String key, String value, long ttlMillis) {
    return map.setIfAbsent(key, value, ttlMillis);
  }

  @Override
  public String get(String key) {
    return map.get(key);
  }

  @Override
  public boolean compareAndDelete(String key, String expectedValue) {
    return map.compareAndDelete(key, expectedValue);
  }

  @Override
  public boolean compareAndSet(String key, String expectedValue, String newValue, long ttlMillis) {
    return map.compareAndSet(key, expectedValue, newValue, ttlMillis);
  }
}
