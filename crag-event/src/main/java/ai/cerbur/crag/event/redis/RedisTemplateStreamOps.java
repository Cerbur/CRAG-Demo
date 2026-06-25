package ai.cerbur.crag.event.redis;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * {@link RedisStreamOps} backed by {@link StringRedisTemplate}, mapping the field-based envelope to
 * Redis Streams commands.
 *
 * <p>Real {@code XREADGROUP}/{@code XPENDING}/{@code XCLAIM} semantics are validated by the Docker
 * HTTP regressions; this adapter only translates calls and does not add behaviour.
 */
public class RedisTemplateStreamOps implements RedisStreamOps {

  private final StringRedisTemplate redis;

  public RedisTemplateStreamOps(StringRedisTemplate redis) {
    this.redis = redis;
  }

  @Override
  public String add(String streamKey, Map<String, String> fields) {
    RecordId id = redis.<String, String>opsForStream().add(streamKey, fields);
    return id == null ? null : id.getValue().toString();
  }

  @Override
  public List<StreamEntry> readNewInGroup(
      String streamKey, String group, String consumer, int count) {
    List<MapRecord<String, String, String>> records =
        redis
            .<String, String>opsForStream()
            .read(
                Consumer.from(group, consumer),
                StreamReadOptions.empty().count(count),
                StreamOffset.create(streamKey, ReadOffset.lastConsumed()));
    if (records == null) {
      return List.of();
    }
    return records.stream()
        .map(
            record ->
                new StreamEntry(
                    record.getId().getValue().toString(), toStringMap(record.getValue())))
        .toList();
  }

  @Override
  public long acknowledge(String streamKey, String group, String recordId) {
    Long acked = redis.opsForStream().acknowledge(streamKey, group, recordId);
    return acked == null ? 0L : acked;
  }

  @Override
  public void ensureGroup(String streamKey, String group) {
    try {
      redis.opsForStream().createGroup(streamKey, ReadOffset.from("0"), group);
    } catch (org.springframework.data.redis.RedisSystemException e) {
      String message = e.getMessage();
      if (message != null && message.contains("BUSYGROUP")) {
        return;
      }
      throw e;
    }
  }

  @Override
  public List<PendingEntry> pending(String streamKey, String group, int count) {
    PendingMessages messages =
        redis.opsForStream().pending(streamKey, group, Range.unbounded(), (long) count);
    if (messages == null) {
      return List.of();
    }
    return messages.stream().map(this::toPendingEntry).toList();
  }

  @Override
  public List<StreamEntry> claim(
      String streamKey,
      String group,
      String consumer,
      Duration minIdleTime,
      List<String> recordIds) {
    RecordId[] ids = recordIds.stream().map(RecordId::of).toArray(RecordId[]::new);
    List<MapRecord<String, String, String>> claimed =
        redis.<String, String>opsForStream().claim(streamKey, group, consumer, minIdleTime, ids);
    if (claimed == null) {
      return List.of();
    }
    return claimed.stream()
        .map(
            record ->
                new StreamEntry(
                    record.getId().getValue().toString(), toStringMap(record.getValue())))
        .toList();
  }

  private PendingEntry toPendingEntry(PendingMessage message) {
    Duration idle = message.getElapsedTimeSinceLastDelivery();
    return new PendingEntry(
        message.getIdAsString(),
        message.getConsumerName(),
        idle == null ? 0L : idle.toMillis(),
        message.getTotalDeliveryCount());
  }

  private static Map<String, String> toStringMap(Map<String, String> raw) {
    return raw == null ? new LinkedHashMap<>() : new LinkedHashMap<>(raw);
  }
}
