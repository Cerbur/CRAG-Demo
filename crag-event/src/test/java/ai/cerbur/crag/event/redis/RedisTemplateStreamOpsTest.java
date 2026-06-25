package ai.cerbur.crag.event.redis;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.lettuce.core.RedisBusyException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * {@link RedisTemplateStreamOps} is the one adapter that talks to real Redis, so its idempotency
 * handling for consumer-group creation is covered here. The fake used by the rest of the redis
 * tests cannot reproduce the real exception wrapping, and the Docker HTTP smoke run proved the
 * previous shallow message check shipped broken: {@code XGROUP CREATE} raises a {@code
 * RedisSystemException} whose own message is the generic {@code "Error in execution"} with the
 * {@code BUSYGROUP} text buried in the wrapped Lettuce cause, so the consumer crashed on every poll
 * once the group persisted across runs.
 */
@DisplayName("RedisTemplateStreamOps")
@SuppressWarnings("unchecked")
class RedisTemplateStreamOpsTest {

  private static final String STREAM = "crag:event:test";
  private static final String GROUP = "test-group";

  @Test
  @DisplayName("treats a wrapped BUSYGROUP (group already exists) as success")
  void ensureGroupIgnoresBusyGroup() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    StreamOperations<String, Object, Object> streamOps = mock(StreamOperations.class);
    when(redis.opsForStream()).thenReturn(streamOps);
    when(streamOps.createGroup(eq(STREAM), any(ReadOffset.class), eq(GROUP)))
        .thenThrow(
            new RedisSystemException(
                "Error in execution",
                new RedisBusyException("BUSYGROUP Consumer Group name already exists")));

    RedisTemplateStreamOps ops = new RedisTemplateStreamOps(redis);

    assertThatCode(() -> ops.ensureGroup(STREAM, GROUP))
        .as("an existing consumer group must be treated as already ensured")
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("rethrows redis errors that are not BUSYGROUP")
  void ensureGroupRethrowsOtherErrors() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    StreamOperations<String, Object, Object> streamOps = mock(StreamOperations.class);
    when(redis.opsForStream()).thenReturn(streamOps);
    when(streamOps.createGroup(eq(STREAM), any(ReadOffset.class), eq(GROUP)))
        .thenThrow(
            new RedisSystemException(
                "Error in execution",
                new RuntimeException(
                    "WRONGTYPE Operation against a key holding the wrong kind of value")));

    RedisTemplateStreamOps ops = new RedisTemplateStreamOps(redis);

    assertThatThrownBy(() -> ops.ensureGroup(STREAM, GROUP))
        .isInstanceOf(RedisSystemException.class);
  }
}
