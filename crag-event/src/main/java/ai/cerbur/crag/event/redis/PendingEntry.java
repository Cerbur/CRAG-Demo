package ai.cerbur.crag.event.redis;

/** A pending (delivered, not yet ACKed) Redis Stream entry with reclaim diagnostics. */
public record PendingEntry(String recordId, String consumer, long idleMillis, long deliveryCount) {}
