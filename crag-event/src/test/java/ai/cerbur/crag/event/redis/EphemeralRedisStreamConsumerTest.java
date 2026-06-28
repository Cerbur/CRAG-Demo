package ai.cerbur.crag.event.redis;

import static org.assertj.core.api.Assertions.assertThat;

import ai.cerbur.crag.event.api.EventEnvelope;
import ai.cerbur.crag.event.api.EventHandler;
import ai.cerbur.crag.event.api.EventHandlerResult;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * EphemeralRedisStreamConsumer 单元测试（plan_21/21.10）。
 *
 * <p>验证：天然幂等 handler 模式，无 JDBC processed_event；成功 ACK；retryable 留 pending；malformed/nonretry DLQ +
 * ACK；handler 抛异常视为 retryable 留 pending。
 */
@DisplayName("EphemeralRedisStreamConsumer")
class EphemeralRedisStreamConsumerTest {

  private static final String STREAM_KEY = "crag:event:access";
  private static final String DLQ_KEY = "crag:event:access:dlq";
  private static final String GROUP = "open-api";
  private static final String CONSUMER = "open-api-1";

  private FakeRedisStreamOps ops;
  private DeadLetterPublisher dlq;
  private final Clock clock = Clock.fixed(Instant.parse("2026-06-29T12:00:00Z"), ZoneOffset.UTC);

  @BeforeEach
  void setUp() {
    ops = new FakeRedisStreamOps();
    dlq = new DeadLetterPublisher(ops, new RedisStreamEventMapper(), DLQ_KEY);
  }

  private EphemeralRedisStreamConsumer consumer(EventHandlerResult result) {
    return new EphemeralRedisStreamConsumer(
        ops,
        new RedisStreamEventMapper(),
        dlq,
        new RecordingHandler(result),
        STREAM_KEY,
        GROUP,
        CONSUMER,
        10);
  }

  private Map<String, String> envelopeFields() {
    return new RedisStreamEventMapper()
        .toFields(
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
                "{\"resourceType\":\"API_KEY\",\"resourceId\":1001,\"tenantId\":5001,"
                    + "\"knowledgeBaseId\":9001,\"action\":\"ROTATED\",\"resourceVersion\":5}"));
  }

  @Test
  @DisplayName("成功 handler 调用后 ACK")
  void successAcks() {
    EphemeralRedisStreamConsumer consumer = consumer(EventHandlerResult.success());
    ops.seed(STREAM_KEY, envelopeFields());

    consumer.processNextBatch();

    assertThat(ops.acknowledgements()).hasSize(1);
  }

  @Test
  @DisplayName("malformed 消息进入 DLQ 并 ACK，不调用 handler")
  void malformedGoesToDlqAndAcks() {
    Map<String, String> malformed = new LinkedHashMap<>(envelopeFields());
    malformed.put(RedisStreamEventMapper.FIELD_EVENT_ID, "not-a-number");
    EphemeralRedisStreamConsumer consumer = consumer(EventHandlerResult.success());
    ops.seed(STREAM_KEY, malformed);

    consumer.processNextBatch();

    assertThat(ops.stream(DLQ_KEY)).hasSize(1);
    assertThat(ops.acknowledgements()).hasSize(1);
  }

  @Test
  @DisplayName("retryable handler 失败不 ACK（留 pending）")
  void retryableFailureLeavesPending() {
    EphemeralRedisStreamConsumer consumer =
        consumer(EventHandlerResult.retryableFailure("transient"));
    ops.seed(STREAM_KEY, envelopeFields());

    consumer.processNextBatch();

    assertThat(ops.acknowledgements()).isEmpty();
    assertThat(ops.stream(DLQ_KEY)).isEmpty();
  }

  @Test
  @DisplayName("non-retryable handler 失败进入 DLQ 并 ACK")
  void nonRetryableFailureGoesToDlqAndAcks() {
    EphemeralRedisStreamConsumer consumer =
        consumer(EventHandlerResult.nonRetryableFailure("fatal"));
    ops.seed(STREAM_KEY, envelopeFields());

    consumer.processNextBatch();

    assertThat(ops.stream(DLQ_KEY)).hasSize(1);
    assertThat(ops.acknowledgements()).hasSize(1);
  }

  @Test
  @DisplayName("handler 抛异常视为 retryable，不 ACK")
  void handlerExceptionLeavesPending() {
    EphemeralRedisStreamConsumer consumer =
        new EphemeralRedisStreamConsumer(
            ops,
            new RedisStreamEventMapper(),
            dlq,
            new ThrowingHandler(new RuntimeException("boom")),
            STREAM_KEY,
            GROUP,
            CONSUMER,
            10);
    ops.seed(STREAM_KEY, envelopeFields());

    consumer.processNextBatch();

    assertThat(ops.acknowledgements()).isEmpty();
    assertThat(ops.stream(DLQ_KEY)).isEmpty();
  }

  @Test
  @DisplayName("重复事件下 handler 每次都被调用（天然幂等模式，无 DB 去重）")
  void duplicateEventsInvokeHandlerEachTime() {
    RecordingHandler handler = new RecordingHandler(EventHandlerResult.success());
    EphemeralRedisStreamConsumer consumer =
        new EphemeralRedisStreamConsumer(
            ops, new RedisStreamEventMapper(), dlq, handler, STREAM_KEY, GROUP, CONSUMER, 10);
    ops.seed(STREAM_KEY, envelopeFields());
    ops.seed(STREAM_KEY, envelopeFields()); // 同一 eventId 第二条

    consumer.processNextBatch();

    // 两条都被处理并 ACK；handler 调用 2 次（无 DB 幂等门）
    assertThat(handler.invocations.get()).isEqualTo(2);
    assertThat(ops.acknowledgements()).hasSize(2);
  }

  private static final class RecordingHandler implements EventHandler {
    final AtomicInteger invocations = new AtomicInteger();
    private final EventHandlerResult result;

    RecordingHandler(EventHandlerResult result) {
      this.result = result;
    }

    @Override
    public String consumerName() {
      return CONSUMER;
    }

    @Override
    public String streamKey() {
      return STREAM_KEY;
    }

    @Override
    public String groupName() {
      return GROUP;
    }

    @Override
    public Set<String> eventTypes() {
      return Set.of("API_KEY_INVALIDATED");
    }

    @Override
    public EventHandlerResult handle(EventEnvelope envelope) {
      invocations.incrementAndGet();
      return result;
    }
  }

  private static final class ThrowingHandler implements EventHandler {
    private final RuntimeException ex;

    ThrowingHandler(RuntimeException ex) {
      this.ex = ex;
    }

    @Override
    public String consumerName() {
      return CONSUMER;
    }

    @Override
    public String streamKey() {
      return STREAM_KEY;
    }

    @Override
    public String groupName() {
      return GROUP;
    }

    @Override
    public Set<String> eventTypes() {
      return Set.of("API_KEY_INVALIDATED");
    }

    @Override
    public EventHandlerResult handle(EventEnvelope envelope) {
      throw ex;
    }
  }
}
