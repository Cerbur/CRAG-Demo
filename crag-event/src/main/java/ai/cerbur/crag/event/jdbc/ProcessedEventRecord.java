package ai.cerbur.crag.event.jdbc;

import ai.cerbur.crag.event.api.ProcessedEventStatus;
import java.time.Instant;

/**
 * Immutable snapshot of a {@code processed_event} row used for consumer idempotency and
 * diagnostics.
 */
public record ProcessedEventRecord(
    String consumerName,
    long eventId,
    String idempotencyKey,
    String eventType,
    String resourceType,
    long resourceId,
    long operationVersion,
    String streamKey,
    String streamRecordId,
    Instant firstSeenAt,
    Instant processedAt,
    ProcessedEventStatus status,
    int handlerAttemptCount,
    String lastErrorCode,
    String lastErrorMessage) {}
