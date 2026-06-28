package ai.cerbur.crag.console.security.jwt;

/**
 * Access JWT 的 {@code kid} 不在本地缓存中（plan_21/21.6）。
 *
 * <p>调用方（{@code AccessJwtKeyRefresher} / verifier）应触发一次在线 {@code GetJwtVerificationKeys}
 * 刷新；刷新后仍未知视为稳定失败，返回 401。
 */
public class UnknownJwtKidException extends RuntimeException {
  public UnknownJwtKidException(String kid) {
    super("unknown kid: " + kid);
  }
}
