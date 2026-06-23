package ai.cerbur.crag.id.redis;

import java.time.Duration;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * Production {@link LeaseOps} backed by {@link StringRedisTemplate}.
 *
 * <p>Atomic compare-and-swap operations use Lua scripts evaluated server-side to avoid race
 * conditions between the GET and SET/DEL calls.
 */
final class RedisTemplateLeaseOps implements LeaseOps {

  private static final DefaultRedisScript<Long> COMPARE_AND_DELETE =
      new DefaultRedisScript<>(
          "if redis.call('GET', KEYS[1]) == ARGV[1] then\n"
              + "  return redis.call('DEL', KEYS[1])\n"
              + "end\n"
              + "return 0",
          Long.class);

  private static final DefaultRedisScript<String> COMPARE_AND_SET =
      new DefaultRedisScript<>(
          "if redis.call('GET', KEYS[1]) == ARGV[1] then\n"
              + "  return redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3])\n"
              + "end\n"
              + "return nil",
          String.class);

  private final StringRedisTemplate redis;

  RedisTemplateLeaseOps(StringRedisTemplate redis) {
    this.redis = redis;
  }

  @Override
  public boolean setIfAbsent(String key, String value, long ttlMillis) {
    Boolean ok = redis.opsForValue().setIfAbsent(key, value, Duration.ofMillis(ttlMillis));
    return Boolean.TRUE.equals(ok);
  }

  @Override
  public String get(String key) {
    return redis.opsForValue().get(key);
  }

  @Override
  public boolean compareAndDelete(String key, String expectedValue) {
    Long result = redis.execute(COMPARE_AND_DELETE, List.of(key), expectedValue);
    return result != null && result > 0;
  }

  @Override
  public boolean compareAndSet(String key, String expectedValue, String newValue, long ttlMillis) {
    String result =
        redis.execute(
            COMPARE_AND_SET, List.of(key), expectedValue, newValue, String.valueOf(ttlMillis));
    return "OK".equals(result);
  }
}
