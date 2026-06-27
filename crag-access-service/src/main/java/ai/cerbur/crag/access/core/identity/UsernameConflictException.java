package ai.cerbur.crag.access.core.identity;

/** 注册时 Username 已被占用的业务异常。gRPC/HTTP 边界映射为 ALREADY_EXISTS。 */
public class UsernameConflictException extends RuntimeException {

  public UsernameConflictException() {
    super("username already exists");
  }
}
