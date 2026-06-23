package ai.cerbur.crag.id.api;

/**
 * Thrown when a caller passes an unparseable or entity-mismatched business ID through the
 * decimal-string API boundary.
 *
 * <p>This exception is NOT thrown for Snowflake internal bit-level encoding errors; those use
 * {@link IllegalArgumentException} or dedicated internal exceptions.
 */
public class InvalidCragIdException extends RuntimeException {

  public InvalidCragIdException(String message) {
    super(message);
  }

  public InvalidCragIdException(String message, Throwable cause) {
    super(message, cause);
  }
}
