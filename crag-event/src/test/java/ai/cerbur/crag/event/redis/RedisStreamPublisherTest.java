package ai.cerbur.crag.event.redis;

import static org.assertj.core.api.Assertions.assertThat;

import ai.cerbur.crag.event.api.EventEnvelope;
import ai.cerbur.crag.event.jdbc.PublishResult;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RedisStreamEventPublisher")
class RedisStreamPublisherTest {

  private static final String STREAM_KEY = "crag:event:knowledge";

  private FakeRedisStreamOps ops;
  private RedisStreamEventPublisher publisher;

  private EventEnvelope envelope() {
    return new EventEnvelope(
        1L,
        "EVENT_SMOKE_CREATED",
        "knowledge-service",
        "SMOKE_EVENT",
        1L,
        1L,
        1,
        Instant.parse("2026-06-25T10:00:00Z"),
        "trace-1",
        "{\"message\":\"smoke\"}");
  }

  @BeforeEach
  void setUp() {
    ops = new FakeRedisStreamOps();
    publisher = new RedisStreamEventPublisher(ops, new RedisStreamEventMapper(), STREAM_KEY);
  }

  @Test
  @DisplayName("a successful write appends the envelope fields and reports delivery")
  void successAppendsAndReports() {
    PublishResult result = publisher.attempt(envelope());

    assertThat(result.outcome()).isEqualTo(PublishResult.Outcome.DELIVERED);
    Map<String, String> written = ops.stream(STREAM_KEY).get(0).fields();
    assertThat(written.get(RedisStreamEventMapper.FIELD_EVENT_ID)).isEqualTo("1");
    assertThat(written.get(RedisStreamEventMapper.FIELD_PAYLOAD))
        .isEqualTo("{\"message\":\"smoke\"}");
  }

  @Test
  @DisplayName("a Redis failure reports a retryable REDIS_UNAVAILABLE result")
  void redisFailureReportsUnavailable() {
    FakeRedisStreamOps failing =
        new FakeRedisStreamOps() {
          @Override
          public String add(String streamKey, Map<String, String> fields) {
            throw new RuntimeException("connection refused");
          }
        };
    RedisStreamEventPublisher failingPublisher =
        new RedisStreamEventPublisher(failing, new RedisStreamEventMapper(), STREAM_KEY);

    PublishResult result = failingPublisher.attempt(envelope());

    assertThat(result.outcome()).isEqualTo(PublishResult.Outcome.FAILED);
    assertThat(result.errorCode().name()).isEqualTo("REDIS_UNAVAILABLE");
  }
}
