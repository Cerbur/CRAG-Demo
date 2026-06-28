package ai.cerbur.crag.open.consumer;

/** {@code API_KEY_INVALIDATED} payload 解析失败。 */
public class InvalidApiKeyInvalidationPayloadException extends Exception {
  public InvalidApiKeyInvalidationPayloadException(String message) {
    super(message);
  }

  public InvalidApiKeyInvalidationPayloadException(String message, Throwable cause) {
    super(message, cause);
  }
}
