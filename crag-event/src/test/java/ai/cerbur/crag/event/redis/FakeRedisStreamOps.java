package ai.cerbur.crag.event.redis;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory {@link RedisStreamOps} for unit-testing consumer, publisher, reclaimer and DLQ logic.
 *
 * <p>This fake models only what the orchestration logic needs; it does not reproduce real Redis
 * {@code XREADGROUP}/{@code XPENDING}/{@code XCLAIM} delivery semantics, which are covered by the
 * Docker HTTP regressions.
 */
public class FakeRedisStreamOps implements RedisStreamOps {

  private final Map<String, List<StreamEntry>> streams = new LinkedHashMap<>();
  private final List<String> acknowledgements = new ArrayList<>();
  private List<PendingEntry> pendingToReturn = List.of();
  private long idCounter = 0;

  /** Seeds a raw entry into {@code streamKey}, used to set up consumer/reclaimer fixtures. */
  public void seed(String streamKey, Map<String, String> fields) {
    streams
        .computeIfAbsent(streamKey, key -> new ArrayList<>())
        .add(new StreamEntry(nextId(), fields));
  }

  public List<StreamEntry> stream(String streamKey) {
    return List.copyOf(streams.getOrDefault(streamKey, List.of()));
  }

  public List<String> acknowledgements() {
    return List.copyOf(acknowledgements);
  }

  public void setPending(List<PendingEntry> pending) {
    this.pendingToReturn = pending;
  }

  @Override
  public String add(String streamKey, Map<String, String> fields) {
    String id = nextId();
    streams.computeIfAbsent(streamKey, key -> new ArrayList<>()).add(new StreamEntry(id, fields));
    return id;
  }

  @Override
  public List<StreamEntry> readNewInGroup(
      String streamKey, String group, String consumer, int count) {
    List<StreamEntry> all = streams.getOrDefault(streamKey, List.of());
    List<StreamEntry> head = all.stream().limit(count).toList();
    List<StreamEntry> tail =
        all.stream()
            .skip(head.size())
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    streams.put(streamKey, tail);
    return head;
  }

  @Override
  public long acknowledge(String streamKey, String group, String recordId) {
    acknowledgements.add(streamKey + ":" + recordId);
    return 1L;
  }

  @Override
  public void ensureGroup(String streamKey, String group) {
    // no-op
  }

  @Override
  public List<PendingEntry> pending(String streamKey, String group, int count) {
    return pendingToReturn.stream().limit(count).toList();
  }

  @Override
  public List<StreamEntry> claim(
      String streamKey,
      String group,
      String consumer,
      Duration minIdleTime,
      List<String> recordIds) {
    List<StreamEntry> entries = streams.getOrDefault(streamKey, List.of());
    return entries.stream().filter(entry -> recordIds.contains(entry.recordId())).toList();
  }

  private String nextId() {
    return (++idCounter) + "-0";
  }
}
