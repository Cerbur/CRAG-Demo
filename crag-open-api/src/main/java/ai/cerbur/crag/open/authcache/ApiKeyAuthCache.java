package ai.cerbur.crag.open.authcache;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Open API Key 本地缓存（plan_21/21.10）。
 *
 * <p>缓存键为完整 Key 的 SHA-256 指纹，<strong>不</strong>保存完整 Key。缓存值只含 {@link CachedApiKey} 的定位与版本水位。默认 TTL
 * 30 秒、最大容量 10,000 项；进程重启后缓存为空。
 *
 * <p>版本水位：失效事件通过 {@link #observeInvalidation(long, long, long)} 记录已观察的 keyVersion/scopeVersion； 后续
 * {@link #put(String, CachedApiKey)} 拒绝写入低于已知水位的鉴权结果，防止 event-before-put 竞态下旧版本回填。
 *
 * <p>定向失效：{@link #evictByApiKeyId(long)} 按 Key 失效事件（disable/rotate/revoke）清理； {@link
 * #evictByKnowledgeBaseId(long)} 按 Scope 失效事件（BLOCKED）清理该 KB 下全部缓存。
 *
 * <p>本缓存无数据库、无持久化；Redis 不可用时不阻止 Access 在线鉴权（由调用方降级处理）。
 */
public final class ApiKeyAuthCache {

  private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthCache.class);

  /** 缓存统计回调，由上层接入 Micrometer。 */
  public interface Metrics {
    void recordHit();

    void recordMiss();

    void recordEviction();

    void recordStaleRejection();
  }

  private final Duration ttl;
  private final int capacity;
  private final Metrics metrics;
  private final Clock clock;

  // 主索引：SHA-256 指纹 → 条目（按访问顺序维护 LRU 驱逐）。
  private final LinkedHashMap<String, Entry> byFingerprint;
  // 二级索引：apiKeyId → 指纹集合（Key 事件定向 evict）。
  private final Map<Long, String> fingerprintByApiKeyId;
  // 二级索引：knowledgeBaseId → 指纹集合（Scope 事件定向 evict）。
  private final Map<Long, java.util.Set<String>> fingerprintsByKbId;
  // 版本水位：apiKeyId → 已观察的最大 (keyVersion, scopeVersion)。
  private final Map<Long, Watermark> watermarks;

  /**
   * @param ttl 默认 TTL（超过后 get 未命中并驱逐）
   * @param capacity 最大条目数；超过时驱逐最旧条目
   * @param metrics 统计回调
   * @param clock 时间源，用于 TTL 与可测试
   */
  public ApiKeyAuthCache(Duration ttl, int capacity, Metrics metrics, Clock clock) {
    if (ttl == null || ttl.isNegative() || ttl.isZero()) {
      throw new IllegalArgumentException("ttl must be positive");
    }
    if (capacity <= 0) {
      throw new IllegalArgumentException("capacity must be positive");
    }
    this.ttl = ttl;
    this.capacity = capacity;
    this.metrics = metrics == null ? new NoopMetrics() : metrics;
    this.clock = clock == null ? Clock.systemUTC() : clock;
    this.byFingerprint = new LinkedHashMap<>(16, 0.75f, true);
    this.fingerprintByApiKeyId = new HashMap<>();
    this.fingerprintsByKbId = new HashMap<>();
    this.watermarks = new HashMap<>();
  }

  /**
   * 写入鉴权结果。若完整 Key 对应的 apiKeyId 已观察过更高 keyVersion/scopeVersion（失效事件先到达），则拒绝写入并 上报 staleRejection。
   *
   * @param rawKey 完整 Key，仅用于计算 SHA-256 指纹，不保存
   * @param value 鉴权结果（不含完整 Key）
   * @throws IllegalStateException 当 value 的版本低于已知水位
   */
  public synchronized void put(String rawKey, CachedApiKey value) {
    if (value == null) {
      throw new IllegalArgumentException("value must not be null");
    }
    Watermark observed = watermarks.get(value.apiKeyId());
    if (observed != null
        && (value.keyVersion() < observed.keyVersion()
            || value.scopeVersion() < observed.scopeVersion())) {
      log.debug(
          "拒绝写入旧版本缓存 — apiKeyId={} keyVersion={}/{} scopeVersion={}/{}",
          value.apiKeyId(),
          value.keyVersion(),
          observed.keyVersion(),
          value.scopeVersion(),
          observed.scopeVersion());
      metrics.recordStaleRejection();
      throw new IllegalStateException(
          "stale auth result rejected: apiKeyId="
              + value.apiKeyId()
              + " keyVersion="
              + value.keyVersion()
              + " scopeVersion="
              + value.scopeVersion());
    }

    String fingerprint = fingerprintOf(rawKey);
    Instant now = Instant.now(clock);
    Entry entry = new Entry(value, now.plus(ttl));
    byFingerprint.put(fingerprint, entry);
    fingerprintByApiKeyId.put(value.apiKeyId(), fingerprint);
    fingerprintsByKbId
        .computeIfAbsent(value.knowledgeBaseId(), k -> new java.util.HashSet<>())
        .add(fingerprint);
    evictIfOverCapacity();
  }

  /**
   * 按完整 Key 查询。过期或不存在返回 {@link Optional#empty()} 并上报 miss/eviction。
   *
   * @param rawKey 完整 Key
   */
  public synchronized Optional<CachedApiKey> get(String rawKey) {
    if (rawKey == null || rawKey.isBlank()) {
      return Optional.empty();
    }
    String fingerprint = fingerprintOf(rawKey);
    Entry entry = byFingerprint.get(fingerprint);
    if (entry == null) {
      metrics.recordMiss();
      return Optional.empty();
    }
    if (Instant.now(clock).isAfter(entry.expiresAt())) {
      removeEntry(fingerprint, entry.value());
      metrics.recordEviction();
      metrics.recordMiss();
      return Optional.empty();
    }
    metrics.recordHit();
    return Optional.of(entry.value());
  }

  /**
   * 按 apiKeyId 定向清理（Key 失效事件：disable/rotate/revoke）。
   *
   * @return 是否清理了条目
   */
  public synchronized boolean evictByApiKeyId(long apiKeyId) {
    String fingerprint = fingerprintByApiKeyId.remove(apiKeyId);
    if (fingerprint == null) {
      return false;
    }
    Entry entry = byFingerprint.remove(fingerprint);
    if (entry == null) {
      return false;
    }
    removeFromKbIndex(fingerprint, entry.value().knowledgeBaseId());
    metrics.recordEviction();
    return true;
  }

  /**
   * 按 knowledgeBaseId 定向清理（Scope 失效事件：BLOCKED）。清理该 KB 下全部缓存。
   *
   * @return 清理条目数
   */
  public synchronized int evictByKnowledgeBaseId(long knowledgeBaseId) {
    java.util.Set<String> fingerprints = fingerprintsByKbId.remove(knowledgeBaseId);
    if (fingerprints == null || fingerprints.isEmpty()) {
      return 0;
    }
    int removed = 0;
    for (String fingerprint : fingerprints) {
      Entry entry = byFingerprint.remove(fingerprint);
      if (entry != null) {
        fingerprintByApiKeyId.remove(entry.value().apiKeyId());
        removed++;
        metrics.recordEviction();
      }
    }
    return removed;
  }

  /**
   * 记录失效事件观察到的版本水位。后续 {@link #put} 拒绝低于该水位的鉴权结果。
   *
   * @param apiKeyId API Key ID
   * @param keyVersion 失效事件携带的 keyVersion
   * @param scopeVersion 失效事件携带的 scopeVersion
   */
  public synchronized void observeInvalidation(long apiKeyId, long keyVersion, long scopeVersion) {
    watermarks.merge(apiKeyId, new Watermark(keyVersion, scopeVersion), Watermark::max);
  }

  @Override
  public synchronized String toString() {
    // 不暴露完整 Key；只暴露条目数与配置。
    return "ApiKeyAuthCache{size="
        + byFingerprint.size()
        + ", capacity="
        + capacity
        + ", ttl="
        + ttl
        + "}";
  }

  // ---- 内部 ----

  private void evictIfOverCapacity() {
    while (byFingerprint.size() > capacity) {
      Map.Entry<String, Entry> oldest = byFingerprint.entrySet().iterator().next();
      String fingerprint = oldest.getKey();
      CachedApiKey value = oldest.getValue().value();
      byFingerprint.remove(fingerprint);
      fingerprintByApiKeyId.remove(value.apiKeyId());
      removeFromKbIndex(fingerprint, value.knowledgeBaseId());
      metrics.recordEviction();
    }
  }

  private void removeEntry(String fingerprint, CachedApiKey value) {
    byFingerprint.remove(fingerprint);
    fingerprintByApiKeyId.remove(value.apiKeyId());
    removeFromKbIndex(fingerprint, value.knowledgeBaseId());
  }

  private void removeFromKbIndex(String fingerprint, long knowledgeBaseId) {
    java.util.Set<String> fingerprints = fingerprintsByKbId.get(knowledgeBaseId);
    if (fingerprints != null) {
      fingerprints.remove(fingerprint);
      if (fingerprints.isEmpty()) {
        fingerprintsByKbId.remove(knowledgeBaseId);
      }
    }
  }

  /** 计算完整 Key 的 SHA-256 指纹（十六进制小写）。 */
  static String fingerprintOf(String rawKey) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(rawKey.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 是 JDK 保证存在的算法
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }

  private record Entry(CachedApiKey value, Instant expiresAt) {}

  private record Watermark(long keyVersion, long scopeVersion) {
    static Watermark max(Watermark a, Watermark b) {
      return new Watermark(
          Math.max(a.keyVersion, b.keyVersion), Math.max(a.scopeVersion, b.scopeVersion));
    }
  }

  /** 默认无操作 Metrics，避免上层未接入时 NPE。 */
  private static final class NoopMetrics implements Metrics {
    @Override
    public void recordHit() {}

    @Override
    public void recordMiss() {}

    @Override
    public void recordEviction() {}

    @Override
    public void recordStaleRejection() {}
  }
}
