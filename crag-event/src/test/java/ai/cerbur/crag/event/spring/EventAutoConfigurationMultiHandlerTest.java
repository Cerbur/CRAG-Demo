package ai.cerbur.crag.event.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.cerbur.crag.event.api.EventEnvelope;
import ai.cerbur.crag.event.api.EventHandler;
import ai.cerbur.crag.event.api.EventHandlerResult;
import ai.cerbur.crag.event.jdbc.JdbcProcessedEventDao;
import ai.cerbur.crag.event.redis.DeadLetterPublisher;
import ai.cerbur.crag.event.redis.FakeRedisStreamOps;
import ai.cerbur.crag.event.redis.RedisStreamEventMapper;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 验证 {@link EventAutoConfiguration} 的多 EventHandler 消费调度（plan_21 缺陷 ② 回归）。
 *
 * <p>正式事件拓扑要求同一服务可注册多个 {@link EventHandler}，各自消费独立的 Redis stream/group（例如 Knowledge 同时消费 {@code
 * INGESTION_*}（{@code crag:event:ingestion}）与 smoke 事件（{@code crag:event:knowledge}）；RAG 消费 {@code
 * DOC_UPLOADED}（{@code crag:event:knowledge}））。旧实现用 {@code ObjectProvider.getIfAvailable()}（单
 * handler 假设）+ 全局 {@code EventProperties} stream/group， 多 handler 时抛 {@code
 * NoUniqueBeanDefinitionException} 且只用单一 stream。本测试断言每个 handler 在各自 stream/group 上被独立消费。
 */
@DisplayName("EventAutoConfiguration multi-handler polling")
class EventAutoConfigurationMultiHandlerTest {

  @Test
  @DisplayName("pollAllHandlers 为每个 EventHandler 在各自 stream/group 上独立消费（多 handler 不冲突）")
  void pollAllHandlersProcessesEveryHandlerOnItsOwnStream() {
    FakeRedisStreamOps ops = new FakeRedisStreamOps();
    RedisStreamEventMapper mapper = new RedisStreamEventMapper();
    JdbcProcessedEventDao dao = mock(JdbcProcessedEventDao.class);
    when(dao.insertPlaceholder(anyString(), any(), anyString(), anyString(), anyString(), any()))
        .thenReturn(true);
    DeadLetterPublisher dlq = new DeadLetterPublisher(ops, mapper, "crag:event:test:dlq");
    EventProperties properties = new EventProperties();

    RecordingHandler ingestionHandler =
        new RecordingHandler(
            "crag:event:ingestion",
            "knowledge-ingestion",
            "knowledge-ingestion-1",
            "INGESTION_READY");
    RecordingHandler docHandler =
        new RecordingHandler(
            "crag:event:knowledge", "rag-ingestion", "rag-ingestion-1", "DOC_UPLOADED");

    // 两个 handler 的 stream 各 seed 一条事件；若用单一全局 stream 只有一个 handler 会命中。
    ops.seed("crag:event:ingestion", envelope(mapper, "INGESTION_READY", 1L, 1L));
    ops.seed("crag:event:knowledge", envelope(mapper, "DOC_UPLOADED", 2L, 2L));

    EventAutoConfiguration.pollAllHandlers(
        List.of(ingestionHandler, docHandler), dao, ops, mapper, dlq, properties);

    assertThat(ingestionHandler.invocations.get())
        .as("ingestion handler 在 crag:event:ingestion 上被独立消费")
        .isEqualTo(1);
    assertThat(docHandler.invocations.get())
        .as("doc handler 在 crag:event:knowledge 上被独立消费")
        .isEqualTo(1);
    assertThat(ops.acknowledgements()).as("两个 stream 各 ACK 一次").hasSize(2);
  }

  @Test
  @DisplayName("pollAllHandlers 无 handler 时不消费也不抛异常")
  void pollAllHandlersNoHandlersIsNoOp() {
    FakeRedisStreamOps ops = new FakeRedisStreamOps();
    RedisStreamEventMapper mapper = new RedisStreamEventMapper();
    JdbcProcessedEventDao dao = mock(JdbcProcessedEventDao.class);
    DeadLetterPublisher dlq = new DeadLetterPublisher(ops, mapper, "crag:event:test:dlq");

    // 无 handler：default profile 下尚未注册任何 EventHandler 的安全行为。
    EventAutoConfiguration.pollAllHandlers(List.of(), dao, ops, mapper, dlq, new EventProperties());

    assertThat(ops.acknowledgements()).isEmpty();
  }

  private static java.util.Map<String, String> envelope(
      RedisStreamEventMapper mapper, String eventType, long eventId, long resourceId) {
    return mapper.toFields(
        new EventEnvelope(
            eventId,
            eventType,
            "producer-service",
            "DOCUMENT",
            resourceId,
            1L,
            1,
            Instant.parse("2026-06-29T10:00:00Z"),
            "trace-" + eventId,
            "{\"message\":\"payload\"}"));
  }

  private static final class RecordingHandler implements EventHandler {
    private final AtomicInteger invocations = new AtomicInteger();
    private final String streamKey;
    private final String groupName;
    private final String consumerName;
    private final Set<String> eventTypes;

    RecordingHandler(String streamKey, String groupName, String consumerName, String eventType) {
      this.streamKey = streamKey;
      this.groupName = groupName;
      this.consumerName = consumerName;
      this.eventTypes = Set.of(eventType);
    }

    @Override
    public String consumerName() {
      return consumerName;
    }

    @Override
    public String streamKey() {
      return streamKey;
    }

    @Override
    public String groupName() {
      return groupName;
    }

    @Override
    public Set<String> eventTypes() {
      return eventTypes;
    }

    @Override
    public EventHandlerResult handle(EventEnvelope envelope) {
      invocations.incrementAndGet();
      return EventHandlerResult.success();
    }
  }
}
