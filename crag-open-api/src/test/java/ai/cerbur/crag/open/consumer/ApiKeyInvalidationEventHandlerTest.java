package ai.cerbur.crag.open.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import ai.cerbur.crag.event.api.EventEnvelope;
import ai.cerbur.crag.event.api.EventHandlerResult;
import ai.cerbur.crag.open.authcache.ApiKeyAuthCache;
import ai.cerbur.crag.open.authcache.CachedApiKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * ApiKeyInvalidationEventHandler 单元测试（plan_21/21.10）。
 *
 * <p>使用真实 {@link ApiKeyAuthCache} 实例，观察 handler 副作用：定向 eviction 与版本水位。 验证：API_KEY 资源按 apiKeyId
 * evict + 水位；API_KEY_SCOPE 资源按 knowledgeBaseId evict + 水位；非目标事件 ACK；未知 payload version DLQ；非法
 * payload DLQ；未知 resourceType DLQ。
 */
@DisplayName("ApiKeyInvalidationEventHandler")
class ApiKeyInvalidationEventHandlerTest {

  private ApiKeyAuthCache cache;
  private ApiKeyInvalidationEventHandler handler;
  private final ObjectMapper objectMapper = new ObjectMapper();

  @BeforeEach
  void setUp() {
    cache =
        new ApiKeyAuthCache(
            Duration.ofSeconds(30),
            100,
            new NoopMetrics(),
            Clock.fixed(Instant.parse("2026-06-29T00:00:00Z"), ZoneOffset.UTC));
    handler =
        new ApiKeyInvalidationEventHandler(
            objectMapper, cache, "crag:event:access", "open-invalidation", "open-invalidation-1");
  }

  @Test
  @DisplayName("API_KEY 资源：按 apiKeyId evict，旧版本不再可写入（水位生效）")
  void apiKeyEventEvictsByApiKeyIdAndRaisesWatermark() {
    // 预置缓存条目
    cache.put("crag_k1_s1", value(1001L, 9001L, 1L, 1L));
    assertThat(cache.get("crag_k1_s1")).isPresent();

    EventEnvelope envelope =
        envelope(
            "API_KEY",
            1001L,
            5L,
            "{\"resourceType\":\"API_KEY\",\"resourceId\":1001,\"tenantId\":5001,"
                + "\"knowledgeBaseId\":9001,\"action\":\"ROTATED\",\"resourceVersion\":5}");

    EventHandlerResult result = handler.handle(envelope);

    assertThat(result.outcome()).isEqualTo(EventHandlerResult.Outcome.COMPLETE);
    // 定向 eviction
    assertThat(cache.get("crag_k1_s1")).isEmpty();
    // 版本水位：再写入 keyVersion=4 的旧鉴权结果应被拒绝
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> cache.put("crag_k1_s1", value(1001L, 9001L, 4L, 1L)))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("API_KEY_SCOPE 资源：按 knowledgeBaseId evict 该 KB 下全部缓存")
  void scopeEventEvictsByKnowledgeBaseId() {
    cache.put("crag_k1_s1", value(1001L, 9001L, 1L, 1L));
    cache.put("crag_k2_s1", value(1002L, 9001L, 1L, 1L)); // 同一 KB

    EventEnvelope envelope =
        envelope(
            "API_KEY_SCOPE",
            9001L,
            3L,
            "{\"resourceType\":\"API_KEY_SCOPE\",\"resourceId\":9001,\"tenantId\":5001,"
                + "\"knowledgeBaseId\":9001,\"action\":\"BLOCKED\",\"resourceVersion\":3}");

    EventHandlerResult result = handler.handle(envelope);

    assertThat(result.outcome()).isEqualTo(EventHandlerResult.Outcome.COMPLETE);
    assertThat(cache.get("crag_k1_s1")).isEmpty();
    assertThat(cache.get("crag_k2_s1")).isEmpty();
  }

  @Test
  @DisplayName("非 API_KEY_INVALIDATED 事件 → success（ACK），不触发 eviction")
  void nonTargetEventSuccess() {
    EventEnvelope envelope =
        new EventEnvelope(
            1L,
            "KNOWLEDGE_BASE_CREATED",
            "knowledge-service",
            "KNOWLEDGE_BASE",
            9001L,
            1L,
            1,
            Instant.parse("2026-06-29T10:00:00Z"),
            "trace-1",
            "{}");

    EventHandlerResult result = handler.handle(envelope);

    assertThat(result.outcome()).isEqualTo(EventHandlerResult.Outcome.COMPLETE);
    assertThat(cache.get("crag_k1_s1")).isEmpty(); // 从未写入
  }

  @Test
  @DisplayName("未知 payload version → DEAD_LETTER")
  void unknownPayloadVersionDeadLettered() {
    EventEnvelope envelope =
        new EventEnvelope(
            1L,
            "API_KEY_INVALIDATED",
            "access-service",
            "API_KEY",
            1001L,
            5L,
            99,
            Instant.parse("2026-06-29T10:00:00Z"),
            "trace-1",
            "{}");

    EventHandlerResult result = handler.handle(envelope);

    assertThat(result.outcome()).isEqualTo(EventHandlerResult.Outcome.DEAD_LETTER);
  }

  @Test
  @DisplayName("payload 缺少必填字段 → DEAD_LETTER（安全 DLQ）")
  void invalidPayloadDeadLettered() {
    // payload 是合法 JSON，但缺少 resourceId 等必填字段 → 解析失败
    EventEnvelope envelope =
        new EventEnvelope(
            1L,
            "API_KEY_INVALIDATED",
            "access-service",
            "API_KEY",
            1001L,
            5L,
            1,
            Instant.parse("2026-06-29T10:00:00Z"),
            "trace-1",
            "{\"resourceType\":\"API_KEY\",\"tenantId\":5001}");

    EventHandlerResult result = handler.handle(envelope);

    assertThat(result.outcome()).isEqualTo(EventHandlerResult.Outcome.DEAD_LETTER);
  }

  @Test
  @DisplayName("未知 resourceType → DEAD_LETTER")
  void unknownResourceTypeDeadLettered() {
    EventEnvelope envelope =
        envelope(
            "UNKNOWN_TYPE",
            1001L,
            5L,
            "{\"resourceType\":\"UNKNOWN_TYPE\",\"resourceId\":1001,\"tenantId\":5001,"
                + "\"knowledgeBaseId\":9001,\"action\":\"X\",\"resourceVersion\":5}");

    EventHandlerResult result = handler.handle(envelope);

    assertThat(result.outcome()).isEqualTo(EventHandlerResult.Outcome.DEAD_LETTER);
  }

  @Test
  @DisplayName("重复 API_KEY 事件天然幂等：二次处理仍 success")
  void duplicateEventIdempotent() {
    EventEnvelope envelope =
        envelope(
            "API_KEY",
            1001L,
            5L,
            "{\"resourceType\":\"API_KEY\",\"resourceId\":1001,\"tenantId\":5001,"
                + "\"knowledgeBaseId\":9001,\"action\":\"ROTATED\",\"resourceVersion\":5}");

    handler.handle(envelope);
    EventHandlerResult result2 = handler.handle(envelope);

    assertThat(result2.outcome()).isEqualTo(EventHandlerResult.Outcome.COMPLETE);
  }

  @Test
  @DisplayName("event name / group / consumer 配置正确")
  void configurationMetadata() {
    assertThat(handler.consumerName()).isEqualTo("open-invalidation-1");
    assertThat(handler.streamKey()).isEqualTo("crag:event:access");
    assertThat(handler.groupName()).isEqualTo("open-invalidation");
    assertThat(handler.eventTypes()).containsExactly("API_KEY_INVALIDATED");
  }

  private EventEnvelope envelope(
      String resourceType, long resourceId, long resourceVersion, String payload) {
    return new EventEnvelope(
        1L,
        "API_KEY_INVALIDATED",
        "access-service",
        resourceType,
        resourceId,
        resourceVersion,
        1,
        Instant.parse("2026-06-29T10:00:00Z"),
        "trace-1",
        payload);
  }

  private CachedApiKey value(long apiKeyId, long kbId, long keyVersion, long scopeVersion) {
    return new CachedApiKey(
        apiKeyId, 5001L, kbId, keyVersion, scopeVersion, Instant.parse("2026-06-29T00:01:00Z"));
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
}
