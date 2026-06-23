package ai.cerbur.crag.id.redis;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minimal in-memory fake that mimics Redis SET NX / GET / DEL for lease tests.
 *
 * <p>TTL is tracked as an absolute expiry; the fake does NOT auto-expire keys on a background
 * thread — tests that need expiry simulation must call {@link #expire(String)} or {@link
 * #advanceTimeAndExpire(long)} explicitly.
 */
final class FakeRedisMap {

  private final Map<String, String> store = new ConcurrentHashMap<>();
  private final Map<String, Long> expiries = new ConcurrentHashMap<>();

  private long currentTimeMillis = 100_000L;

  /** Absolute-time key: lower layer uses this to check expiry. */
  String get(String key) {
    Long exp = expiries.get(key);
    if (exp != null && currentTimeMillis >= exp) {
      store.remove(key);
      expiries.remove(key);
      return null;
    }
    return store.get(key);
  }

  /**
   * SET NX: set only if key does not already have a non-expired value.
   *
   * @return {@code true} if the key was set
   */
  boolean setIfAbsent(String key, String value, long ttlMillis) {
    Long exp = expiries.get(key);
    if (exp != null && currentTimeMillis >= exp) {
      store.remove(key);
      expiries.remove(key);
    }
    if (store.containsKey(key)) {
      return false;
    }
    store.put(key, value);
    expiries.put(key, currentTimeMillis + ttlMillis);
    return true;
  }

  /**
   * Compare-and-delete: remove the key only if its current value matches {@code expectedValue}.
   *
   * @return {@code true} if the key was deleted
   */
  boolean compareAndDelete(String key, String expectedValue) {
    String current = get(key);
    if (current != null && current.equals(expectedValue)) {
      store.remove(key);
      expiries.remove(key);
      return true;
    }
    return false;
  }

  /**
   * Compare-and-set: if key exists and current value equals {@code expectedValue}, set it to {@code
   * newValue} and reset TTL.
   *
   * @return {@code true} if the value was updated
   */
  boolean compareAndSet(String key, String expectedValue, String newValue, long ttlMillis) {
    String current = get(key);
    if (current != null && current.equals(expectedValue)) {
      store.put(key, newValue);
      expiries.put(key, currentTimeMillis + ttlMillis);
      return true;
    }
    return false;
  }

  /** Manually expire a key to simulate TTL passing. */
  void expire(String key) {
    store.remove(key);
    expiries.remove(key);
  }

  /** Advance the fake clock by the given millis and expire any keys that are past their TTL. */
  void advanceTimeAndExpire(long deltaMillis) {
    currentTimeMillis += deltaMillis;
    expiries
        .entrySet()
        .removeIf(
            e -> {
              if (currentTimeMillis >= e.getValue()) {
                store.remove(e.getKey());
                return true;
              }
              return false;
            });
  }

  /** For assertions. */
  boolean containsKey(String key) {
    return get(key) != null;
  }
}
