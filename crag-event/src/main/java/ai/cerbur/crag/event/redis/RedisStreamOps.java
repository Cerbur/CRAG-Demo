package ai.cerbur.crag.event.redis;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Stream operations the event infrastructure needs, abstracted so production uses {@code
 * StringRedisTemplate} and tests use an in-memory fake.
 *
 * <p>The fake cannot reproduce real {@code XREADGROUP}/{@code XPENDING}/{@code XCLAIM} semantics;
 * real Redis Streams behaviour is proven by the Docker HTTP regressions, while this interface keeps
 * the consumer and reclaimer logic unit-testable.
 */
public interface RedisStreamOps {

  /** Appends a field map to {@code streamKey} and returns the new record id. */
  String add(String streamKey, Map<String, String> fields);

  /**
   * Reads never-delivered messages for the consumer group (Redis {@code >} offset), up to {@code
   * count}.
   */
  List<StreamEntry> readNewInGroup(String streamKey, String group, String consumer, int count);

  /** Acknowledges {@code recordId} in the group. Returns acknowledged count. */
  long acknowledge(String streamKey, String group, String recordId);

  /** Creates the consumer group if absent; a BUSYGROUP result is treated as success. */
  void ensureGroup(String streamKey, String group);

  /**
   * Returns up to {@code count} pending entries for the group with idle time and delivery count.
   */
  List<PendingEntry> pending(String streamKey, String group, int count);

  /**
   * Claims the given record ids for {@code consumer} when they have been idle at least {@code
   * minIdleTime}; returns the claimed entries (with their fields).
   */
  List<StreamEntry> claim(
      String streamKey,
      String group,
      String consumer,
      Duration minIdleTime,
      List<String> recordIds);
}
