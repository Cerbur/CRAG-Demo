package ai.cerbur.crag.console.security.jwt;

/**
 * Access JWT 验签或声明校验失败（plan_21/21.6）。
 *
 * <p>覆盖：算法不符、签名错误、过期、未生效、iss/aud 不匹配、格式错误等。Bearer filter 捕获后返回 401，不泄漏具体原因。
 */
public class InvalidJwtException extends RuntimeException {
  public InvalidJwtException(String message) {
    super(message);
  }
}
