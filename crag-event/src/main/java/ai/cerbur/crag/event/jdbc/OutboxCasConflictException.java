package ai.cerbur.crag.event.jdbc;

/**
 * Raised when an outbox version-CAS update affects no rows.
 *
 * <p>This means another publisher reclaimed the row after the current claim expired (or raced the
 * initial claim). It is benign: the winning publisher takes responsibility for the event, so the
 * caller logs at debug and skips rather than retrying blindly.
 */
public class OutboxCasConflictException extends RuntimeException {

  public OutboxCasConflictException(String message) {
    super(message);
  }
}
