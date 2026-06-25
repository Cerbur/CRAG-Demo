package ai.cerbur.crag.knowledge.smoke.dto;

/**
 * Creation response for a smoke event. {@code eventId} is a decimal string at the HTTP boundary.
 */
public record KnowledgeSmokeEventResponse(String eventId, String runId, String outboxStatus) {}
