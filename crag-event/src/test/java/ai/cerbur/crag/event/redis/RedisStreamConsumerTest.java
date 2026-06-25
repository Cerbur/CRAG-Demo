package ai.cerbur.crag.event.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.cerbur.crag.event.api.EventEnvelope;
import ai.cerbur.crag.event.api.EventErrorCode;
import ai.cerbur.crag.event.api.EventHandler;
import ai.cerbur.crag.event.api.EventHandlerResult;
import ai.cerbur.crag.event.api.ProcessedEventStatus;
import ai.cerbur.crag.event.jdbc.JdbcProcessedEventDao;
import ai.cerbur.crag.event.jdbc.ProcessedEventRecord;
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

@DisplayName("RedisStreamEventConsumer")
class RedisStreamConsumerTest {

  private static final String STREAM_KEY = "crag:event:knowledge";
  private static final String DLQ_KEY = "crag:event:knowledge:dlq";
  private static final String GROUP = "knowledge-smoke";
  private static final String CONSUMER = "knowledge-smoke-1";

  private FakeRedisStreamOps ops;
  private JdbcProcessedEventDao dao;
  private DeadLetterPublisher dlq;
  private RecordingHandler handler;
  private final Clock clock = Clock.fixed(Instant.parse("2026-06-25T12:00:00Z"), ZoneOffset.UTC);

  private RedisStreamEventConsumer consumer(EventHandlerResult result) {
    handler = new RecordingHandler(result);
    return new RedisStreamEventConsumer(
        ops,
        new RedisStreamEventMapper(),
        dao,
        dlq,
        handler,
        STREAM_KEY,
        GROUP,
        CONSUMER,
        10,
        clock);
  }

  private Map<String, String> envelopeFields() {
    return new RedisStreamEventMapper()
        .toFields(
            new EventEnvelope(
                1L,
                "EVENT_SMOKE_CREATED",
                "knowledge-service",
                "SMOKE_EVENT",
                1L,
                1L,
                1,
                Instant.parse("2026-06-25T10:00:00Z"),
                "trace-1",
                "{\"message\":\"smoke\"}"));
  }

  @BeforeEach
  void setUp() {
    ops = new FakeRedisStreamOps();
    dao = mock(JdbcProcessedEventDao.class);
    dlq = new DeadLetterPublisher(ops, new RedisStreamEventMapper(), DLQ_KEY);
  }

  @Test
  @DisplayName("a successful handler run marks the event processed and ACKs")
  void successMarksProcessedAndAcks() {
    when(dao.insertPlaceholder(anyString(), any(), anyString(), anyString(), anyString(), any()))
        .thenReturn(true);
    RedisStreamEventConsumer consumer = consumer(EventHandlerResult.success());
    ops.seed(STREAM_KEY, envelopeFields());

    consumer.processNextBatch();

    verify(dao).markProcessed(eq(CONSUMER), eq(1L), anyString(), any());
    assertThat(ops.acknowledgements()).hasSize(1);
    assertThat(handler.invocations.get()).isEqualTo(1);
  }

  @Test
  @DisplayName("a malformed message is dead-lettered and ACKed without invoking the handler")
  void malformedIsDeadLettered() {
    Map<String, String> malformed = new LinkedHashMap<>(envelopeFields());
    malformed.put(RedisStreamEventMapper.FIELD_EVENT_ID, "not-a-number");
    RedisStreamEventConsumer consumer = consumer(EventHandlerResult.success());
    ops.seed(STREAM_KEY, malformed);

    consumer.processNextBatch();

    assertThat(ops.stream(DLQ_KEY)).hasSize(1);
    assertThat(ops.acknowledgements()).hasSize(1);
    verify(dao, never())
        .insertPlaceholder(anyString(), any(), anyString(), anyString(), anyString(), any());
    assertThat(handler.invocations.get()).isZero();
  }

  @Test
  @DisplayName("a retryable handler failure records the failure and does not ACK")
  void retryableFailureDoesNotAck() {
    when(dao.insertPlaceholder(anyString(), any(), anyString(), anyString(), anyString(), any()))
        .thenReturn(true);
    RedisStreamEventConsumer consumer = consumer(EventHandlerResult.retryableFailure("boom"));
    ops.seed(STREAM_KEY, envelopeFields());

    consumer.processNextBatch();

    verify(dao)
        .markFailed(eq(CONSUMER), eq(1L), eq(EventErrorCode.HANDLER_FAILED), anyString(), any());
    assertThat(ops.acknowledgements()).isEmpty();
  }

  @Test
  @DisplayName("a non-retryable handler failure dead-letters, marks and ACKs")
  void nonRetryableFailureDeadLetters() {
    when(dao.insertPlaceholder(anyString(), any(), anyString(), anyString(), anyString(), any()))
        .thenReturn(true);
    RedisStreamEventConsumer consumer = consumer(EventHandlerResult.nonRetryableFailure("fatal"));
    ops.seed(STREAM_KEY, envelopeFields());

    consumer.processNextBatch();

    assertThat(ops.stream(DLQ_KEY)).hasSize(1);
    verify(dao)
        .markDeadLettered(
            eq(CONSUMER), eq(1L), eq(EventErrorCode.HANDLER_NON_RETRYABLE), anyString(), any());
    assertThat(ops.acknowledgements()).hasSize(1);
  }

  @Test
  @DisplayName("a duplicate of an already-processed event is ACKed without re-running the handler")
  void alreadyProcessedDuplicateIsAcked() {
    when(dao.insertPlaceholder(anyString(), any(), anyString(), anyString(), anyString(), any()))
        .thenReturn(false);
    when(dao.findByEventId(CONSUMER, 1L))
        .thenReturn(
            new ProcessedEventRecord(
                CONSUMER,
                1L,
                "k",
                "EVENT_SMOKE_CREATED",
                "SMOKE_EVENT",
                1L,
                1L,
                STREAM_KEY,
                "1-0",
                Instant.now(clock),
                Instant.now(clock),
                ProcessedEventStatus.PROCESSED,
                1,
                null,
                null));
    RedisStreamEventConsumer consumer = consumer(EventHandlerResult.success());
    ops.seed(STREAM_KEY, envelopeFields());

    consumer.processNextBatch();

    assertThat(ops.acknowledgements()).hasSize(1);
    assertThat(handler.invocations.get()).isZero();
    verify(dao, never()).markProcessed(anyString(), eq(1L), anyString(), any());
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
      return Set.of("EVENT_SMOKE_CREATED");
    }

    @Override
    public EventHandlerResult handle(EventEnvelope envelope) {
      invocations.incrementAndGet();
      return result;
    }
  }
}
