package ai.cerbur.crag.event.redis;

import java.util.Map;

/** A Redis Stream record: its id and the field map. */
public record StreamEntry(String recordId, Map<String, String> fields) {}
