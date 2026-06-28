package ai.cerbur.crag.console.auth.service;

/** Origin/Referer 同站校验失败（plan_21/21.6）。 */
public class InvalidOriginException extends RuntimeException {
  public InvalidOriginException(String message) {
    super(message);
  }
}
