package ai.cerbur.crag.event.api;

/**
 * Stable error codes recorded on {@code outbox_event} / {@code processed_event} rows and in logs.
 *
 * <p>Codes are persisted as their {@link #name()} string; never rename an existing constant. The
 * comment on each constant documents the recovery action the infrastructure takes, so logs and row
 * diagnostics stay self-explanatory without leaking payloads or stack traces.
 */
public enum EventErrorCode {
  /** Redis was unavailable; the publisher retries, the consumer skips the round without ACKing. */
  REDIS_UNAVAILABLE,

  /**
   * Envelope or payload failed structural/JSON validation; the message is dead-lettered and ACKed.
   */
  MESSAGE_MALFORMED,

  /** Handler raised a retryable failure; the message stays pending for reclaim. */
  HANDLER_FAILED,

  /** Handler declared the failure non-retryable; the message is dead-lettered and ACKed. */
  HANDLER_NON_RETRYABLE,

  /** Concurrent publisher claim lost the CAS race; benign, logged at debug. */
  OUTBOX_CAS_CONFLICT,

  /** Publish attempts exhausted the configured maximum; the outbox row moves to DEAD. */
  OUTBOX_EXHAUSTED
}
