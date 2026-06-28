package ai.cerbur.crag.open.authcache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Open API Key 缓存单元测试（plan_21/21.10）。
 *
 * <p>覆盖：TTL 过期、capacity 上限、Key 事件定向 evict、Scope 事件定向 evict、event-before-put 水位拒绝 旧版本、完整 Key 不出现在
 * toString。SHA-256 指纹作为缓存键；缓存值只含定位与版本水位，不含完整 Key。
 */
@DisplayName("ApiKeyAuthCache")
class ApiKeyAuthCacheTest {

  private static final String RAW_KEY = "crag_prefix_abcdef_secretvalue";
  private static final long TENANT_ID = 5001L;

  // 固定时钟：2026-06-29T00:00:00Z；TTL 30s。
  private final Clock clock = Clock.fixed(Instant.parse("2026-06-29T00:00:00Z"), ZoneOffset.UTC);

  private ApiKeyAuthCache cache(Duration ttl, int capacity, ApiKeyAuthCache.Metrics metrics) {
    return new ApiKeyAuthCache(ttl, capacity, metrics, clock);
  }

  private ApiKeyAuthCache defaultCache() {
    return cache(Duration.ofSeconds(30), 10_000, new NoopMetrics());
  }

  /** 显式构造 CachedApiKey，避免 helper 重载混淆 apiKeyId/keyVersion。 */
  private CachedApiKey value(
      long apiKeyId, long kbId, long keyVersion, long scopeVersion, Instant expiresAt) {
    return new CachedApiKey(apiKeyId, TENANT_ID, kbId, keyVersion, scopeVersion, expiresAt);
  }

  private CachedApiKey value(long apiKeyId, long kbId) {
    return value(apiKeyId, kbId, 1L, 1L, Instant.parse("2026-06-29T00:01:00Z"));
  }

  @Test
  @DisplayName("put 后 get 命中；TTL 未过期前持续命中")
  void putThenGet() {
    ApiKeyAuthCache cache = defaultCache();
    CachedApiKey value = value(1001L, 9001L);

    cache.put(RAW_KEY, value);

    assertThat(cache.get(RAW_KEY)).hasValue(value);
    // 第二次 get 仍命中
    assertThat(cache.get(RAW_KEY)).hasValue(value);
  }

  @Test
  @DisplayName("同一指纹 TTL 过期后 get 未命中（时间推进）")
  void ttlExpiryWithSameInstance() {
    MutableClock mutable = new MutableClock(Instant.parse("2026-06-29T00:00:00Z"));
    ApiKeyAuthCache cache =
        new ApiKeyAuthCache(Duration.ofSeconds(30), 10_000, new NoopMetrics(), mutable);
    CachedApiKey value = value(1001L, 9001L);
    cache.put(RAW_KEY, value);

    mutable.advance(Duration.ofSeconds(29));
    assertThat(cache.get(RAW_KEY)).isPresent();

    mutable.advance(Duration.ofSeconds(2)); // 累计 31s
    assertThat(cache.get(RAW_KEY)).isEmpty();
  }

  @Test
  @DisplayName("容量达到上限时驱逐最旧条目")
  void capacityEvictsOldest() {
    ApiKeyAuthCache cache = cache(Duration.ofSeconds(30), 3, new NoopMetrics());
    cache.put("crag_k1_s1", value(1L, 91L));
    cache.put("crag_k2_s1", value(2L, 92L));
    cache.put("crag_k3_s1", value(3L, 93L));

    // 容量满后再 put 一条，最旧（k1）应被驱逐
    cache.put("crag_k4_s1", value(4L, 94L));

    assertThat(cache.get("crag_k1_s1")).isEmpty();
    assertThat(cache.get("crag_k2_s1")).isPresent();
    assertThat(cache.get("crag_k3_s1")).isPresent();
    assertThat(cache.get("crag_k4_s1")).isPresent();
  }

  @Test
  @DisplayName("按 apiKeyId 定向 evict（Key 事件）")
  void evictByApiKeyId() {
    ApiKeyAuthCache cache = defaultCache();
    cache.put("crag_k1_s1", value(1001L, 9001L));
    cache.put("crag_k2_s1", value(1002L, 9001L));

    boolean removed = cache.evictByApiKeyId(1001L);

    assertThat(removed).isTrue();
    assertThat(cache.get("crag_k1_s1")).isEmpty();
    assertThat(cache.get("crag_k2_s1")).isPresent();

    // 再次 evict 不存在的 key 返回 false
    assertThat(cache.evictByApiKeyId(1001L)).isFalse();
  }

  @Test
  @DisplayName("按 knowledgeBaseId 定向 evict（Scope 事件）")
  void evictByKnowledgeBaseId() {
    ApiKeyAuthCache cache = defaultCache();
    cache.put("crag_k1_s1", value(1001L, 9001L));
    cache.put("crag_k2_s1", value(1002L, 9002L));

    int removed = cache.evictByKnowledgeBaseId(9001L);

    assertThat(removed).isEqualTo(1);
    assertThat(cache.get("crag_k1_s1")).isEmpty();
    assertThat(cache.get("crag_k2_s1")).isPresent();
  }

  @Test
  @DisplayName("Scope 事件清理该 KB 下全部缓存（多个 Key 指向同一 KB）")
  void scopeEvictClearsAllKeysUnderKb() {
    ApiKeyAuthCache cache = defaultCache();
    cache.put("crag_k1_s1", value(1001L, 9001L));
    cache.put("crag_k2_s1", value(1002L, 9001L)); // 同一 KB

    int removed = cache.evictByKnowledgeBaseId(9001L);

    assertThat(removed).isEqualTo(2);
    assertThat(cache.get("crag_k1_s1")).isEmpty();
    assertThat(cache.get("crag_k2_s1")).isEmpty();
  }

  @Test
  @DisplayName("水位拒绝：已知更高 keyVersion 时拒绝写入更低 keyVersion 的鉴权结果")
  void watermarkRejectsLowerKeyVersion() {
    ApiKeyAuthCache cache = defaultCache();
    cache.observeInvalidation(1001L, 5L, 1L);

    CachedApiKey stale = value(1001L, 9001L, 4L, 1L, Instant.parse("2026-06-29T00:01:00Z"));
    assertThatThrownBy(() -> cache.put(RAW_KEY, stale)).isInstanceOf(IllegalStateException.class);

    assertThat(cache.get(RAW_KEY)).isEmpty();
  }

  @Test
  @DisplayName("水位拒绝：已知更高 scopeVersion 时拒绝写入更低 scopeVersion 的鉴权结果")
  void watermarkRejectsLowerScopeVersion() {
    ApiKeyAuthCache cache = defaultCache();
    cache.observeInvalidation(1001L, 1L, 7L);

    CachedApiKey stale = value(1001L, 9001L, 1L, 6L, Instant.parse("2026-06-29T00:01:00Z"));
    assertThatThrownBy(() -> cache.put(RAW_KEY, stale)).isInstanceOf(IllegalStateException.class);

    assertThat(cache.get(RAW_KEY)).isEmpty();
  }

  @Test
  @DisplayName("水位允许：put 等于或高于已知版本的鉴权结果")
  void watermarkAllowsEqualOrHigher() {
    ApiKeyAuthCache cache = defaultCache();
    cache.observeInvalidation(1001L, 5L, 3L);

    // 等版本允许
    CachedApiKey equal = value(1001L, 9001L, 5L, 3L, Instant.parse("2026-06-29T00:01:00Z"));
    cache.put(RAW_KEY, equal);
    assertThat(cache.get(RAW_KEY)).isPresent();

    // 高版本允许（不同 apiKeyId）
    cache.observeInvalidation(1002L, 1L, 1L);
    cache.put("crag_k2_s1", value(1002L, 9001L, 6L, 4L, Instant.parse("2026-06-29T00:01:00Z")));
    assertThat(cache.get("crag_k2_s1")).isPresent();
  }

  @Test
  @DisplayName("水位按 apiKeyId 维度独立，不影响其他 Key")
  void watermarkIsPerApiKeyId() {
    ApiKeyAuthCache cache = defaultCache();
    cache.observeInvalidation(1001L, 5L, 1L);

    // 不同 apiKeyId 不受影响（低版本可写入）
    cache.put("crag_other_s1", value(1002L, 9001L, 1L, 1L, Instant.parse("2026-06-29T00:01:00Z")));
    assertThat(cache.get("crag_other_s1")).isPresent();
  }

  @Test
  @DisplayName("toString 不包含完整 Key")
  void toStringDoesNotLeakRawKey() {
    ApiKeyAuthCache cache = defaultCache();
    cache.put(RAW_KEY, value(1001L, 9001L));

    String repr = cache.toString();
    assertThat(repr).doesNotContain(RAW_KEY);
    assertThat(repr).doesNotContain("abcdef_secretvalue");
    assertThat(repr).doesNotContain("secretvalue");
  }

  @Test
  @DisplayName("SHA-256 指纹不包含完整 Key（单独验证）")
  void fingerprintDoesNotLeakRawKey() {
    String fingerprint = ApiKeyAuthCache.fingerprintOf(RAW_KEY);
    assertThat(fingerprint).doesNotContain(RAW_KEY);
    assertThat(fingerprint).doesNotContain("secretvalue");
    // 64 位十六进制字符
    assertThat(fingerprint).hasSize(64);
    assertThat(fingerprint).matches("[0-9a-f]{64}");
  }

  @Test
  @DisplayName("get null/blank key 返回空")
  void getNullOrEmptyKeyReturnsEmpty() {
    ApiKeyAuthCache cache = defaultCache();
    assertThat(cache.get(null)).isEmpty();
    assertThat(cache.get("")).isEmpty();
    assertThat(cache.get("   ")).isEmpty();
  }

  @Test
  @DisplayName("缓存命中/未命中/驱逐计数通过 Metrics 回调上报")
  void metricsAreRecorded() {
    RecordingMetrics metrics = new RecordingMetrics();
    ApiKeyAuthCache cache = cache(Duration.ofSeconds(30), 100, metrics);
    cache.put(RAW_KEY, value(1001L, 9001L));

    cache.get(RAW_KEY); // 命中
    cache.get("crag_unknown_s1"); // 未命中
    cache.evictByApiKeyId(1001L); // 驱逐

    assertThat(metrics.hits).isEqualTo(1);
    assertThat(metrics.misses).isEqualTo(1);
    assertThat(metrics.evictions).isEqualTo(1);
  }

  @Test
  @DisplayName("stale rejection 通过 Metrics 上报")
  void staleRejectionRecorded() {
    RecordingMetrics metrics = new RecordingMetrics();
    ApiKeyAuthCache cache = cache(Duration.ofSeconds(30), 100, metrics);
    cache.observeInvalidation(1001L, 5L, 1L);

    try {
      cache.put(RAW_KEY, value(1001L, 9001L, 4L, 1L, Instant.parse("2026-06-29T00:01:00Z")));
    } catch (IllegalStateException expected) {
      // expected
    }

    assertThat(metrics.staleRejections).isEqualTo(1);
  }

  // ---- helpers ----

  /** 可手动推进的 Clock，用于 TTL 测试。 */
  private static final class MutableClock extends Clock {
    private Instant instant;

    MutableClock(Instant initial) {
      this.instant = initial;
    }

    void advance(Duration d) {
      this.instant = instant.plus(d);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }

  static final class NoopMetrics implements ApiKeyAuthCache.Metrics {
    @Override
    public void recordHit() {}

    @Override
    public void recordMiss() {}

    @Override
    public void recordEviction() {}

    @Override
    public void recordStaleRejection() {}
  }

  static final class RecordingMetrics implements ApiKeyAuthCache.Metrics {
    int hits;
    int misses;
    int evictions;
    int staleRejections;

    @Override
    public void recordHit() {
      hits++;
    }

    @Override
    public void recordMiss() {
      misses++;
    }

    @Override
    public void recordEviction() {
      evictions++;
    }

    @Override
    public void recordStaleRejection() {
      staleRejections++;
    }
  }
}
