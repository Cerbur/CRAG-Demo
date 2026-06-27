package ai.cerbur.crag.event.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.cerbur.crag.event.api.EventEnvelope;
import ai.cerbur.crag.event.api.EventErrorCode;
import ai.cerbur.crag.event.jdbc.JdbcProcessedEventDao;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RedisPendingReclaimer")
class RedisPendingReclaimerTest {

  private static final String STREAM_KEY = "crag:event:knowledge";
  private static final String DLQ_KEY = "crag:event:knowledge:dlq";
  private static final String GROUP = "knowledge-smoke";
  private static final String CONSUMER = "knowledge-smoke-1";
  private static final String IDEMPOTENCY_KEY = "EVENT_SMOKE_CREATED:SMOKE_EVENT:1:1";

  private FakeRedisStreamOps ops;
  private JdbcProcessedEventDao dao;
  private DeadLetterPublisher dlq;
  private RecordingProcessor processor;
  private final Clock clock = Clock.fixed(Instant.parse("2026-06-25T12:00:00Z"), ZoneOffset.UTC);

  private RedisPendingReclaimer reclaimer(Duration claimIdle, long maxDeliveries) {
    processor = new RecordingProcessor();
    return new RedisPendingReclaimer(
        ops,
        new RedisStreamEventMapper(),
        dao,
        dlq,
        processor,
        STREAM_KEY,
        GROUP,
        CONSUMER,
        claimIdle,
        maxDeliveries,
        10,
        clock);
  }

  private void seedEnvelope(String recordId) {
    ops.seed(
        STREAM_KEY,
        new RedisStreamEventMapper()
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
                    "{\"message\":\"smoke\"}")));
  }

  @BeforeEach
  void setUp() {
    ops = new FakeRedisStreamOps();
    dao = mock(JdbcProcessedEventDao.class);
    dlq = new DeadLetterPublisher(ops, new RedisStreamEventMapper(), DLQ_KEY);
  }

  @Test
  @DisplayName("entries idle below claimIdle are left alone")
  void belowClaimIdleNoAction() {
    ops.setPending(List.of(new PendingEntry("1-0", CONSUMER, 1_000L, 1L)));
    RedisPendingReclaimer reclaimer = reclaimer(Duration.ofSeconds(30), 3);

    reclaimer.reclaimPending();

    assertThat(processor.processed).isEmpty();
    assertThat(ops.acknowledgements()).isEmpty();
  }

  @Test
  @DisplayName("idle entries below the delivery limit are handed back for re-delivery")
  void idleBelowLimitIsRedispatched() {
    seedEnvelope("1-0");
    ops.setPending(List.of(new PendingEntry("1-0", CONSUMER, 60_000L, 2L)));
    RedisPendingReclaimer reclaimer = reclaimer(Duration.ofSeconds(30), 3);

    reclaimer.reclaimPending();

    assertThat(processor.processed).hasSize(1);
    assertThat(ops.acknowledgements()).isEmpty();
  }

  @Test
  @DisplayName("idle entries at the delivery limit are dead-lettered and ACKed")
  void idleAtLimitIsDeadLettered() {
    seedEnvelope("1-0");
    when(dao.markDeadLettered(anyString(), eq(IDEMPOTENCY_KEY), any(), anyString(), any()))
        .thenReturn(true);
    ops.setPending(List.of(new PendingEntry("1-0", CONSUMER, 60_000L, 3L)));
    RedisPendingReclaimer reclaimer = reclaimer(Duration.ofSeconds(30), 3);

    reclaimer.reclaimPending();

    assertThat(ops.stream(DLQ_KEY)).hasSize(1);
    assertThat(ops.acknowledgements()).hasSize(1);
    verify(dao)
        .markDeadLettered(
            eq(CONSUMER),
            eq(IDEMPOTENCY_KEY),
            eq(EventErrorCode.HANDLER_FAILED),
            anyString(),
            any());
    assertThat(processor.processed).isEmpty();
  }

  private static final class RecordingProcessor implements MessageProcessor {
    final List<StreamEntry> processed = new ArrayList<>();

    @Override
    public void process(StreamEntry entry) {
      processed.add(entry);
    }
  }
}
