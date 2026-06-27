package ai.cerbur.crag.access.core.identity;

/**
 * 登录、密码校验或后续 Token 鉴权失败的统一业务异常。
 *
 * <p>不区分 Username 不存在、密码错误、账号禁用或用户禁用，避免泄漏账号是否存在。gRPC/HTTP 边界映射为 UNAUTHENTICATED。
 */
public class InvalidCredentialsException extends RuntimeException {

  public InvalidCredentialsException() {
    super("invalid credentials");
  }
}
