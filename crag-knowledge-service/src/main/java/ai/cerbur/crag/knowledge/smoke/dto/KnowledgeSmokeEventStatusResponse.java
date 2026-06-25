package ai.cerbur.crag.knowledge.smoke.dto;

/**
 * Diagnostic summary for a smoke event, returned by the query endpoints. It surfaces only
 * non-sensitive diagnostics (statuses, attempt counts, stable error codes); it never echoes the
 * full payload.
 */
public record KnowledgeSmokeEventStatusResponse(
    String eventId,
    String runId,
    String outboxStatus,
    String processedStatus,
    int handlerAttemptCount,
    boolean deadLettered,
    String lastErrorCode,
    String lastErrorMessage) {}
