package ai.cerbur.crag.access.grpc.security;

import ai.cerbur.crag.grpc.runtime.identity.GrpcCallerContext;
import io.grpc.Status;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 按 gRPC 调用方收紧 Access RPC：Console 管理身份/会话/成员/Scope/Key；Open API 只能鉴权 API Key 与读取 JWT 公钥。
 *
 * <p>调用方身份来自 gRPC Service Identity（{@link GrpcCallerContext}）。未授权抛 {@link
 * Status#PERMISSION_DENIED}。
 */
@Component
public class AccessRpcAuthorizer {

  static final String CONSOLE_API = "console-api";
  static final String OPEN_API = "open-api";

  @Autowired private GrpcCallerContext callerContext;

  /** 要求调用方为 Console。 */
  public void requireConsole() {
    requireCaller(CONSOLE_API);
  }

  /** 要求调用方为 Open API。 */
  public void requireOpenApi() {
    requireCaller(OPEN_API);
  }

  /** 要求调用方为 Console 或 Open API。 */
  public void requireConsoleOrOpenApi() {
    String caller = callerContext.requireIdentity().serviceName();
    if (!CONSOLE_API.equals(caller) && !OPEN_API.equals(caller)) {
      throw Status.PERMISSION_DENIED.withDescription("caller not allowed").asRuntimeException();
    }
  }

  private void requireCaller(String expected) {
    String caller = callerContext.requireIdentity().serviceName();
    if (!expected.equals(caller)) {
      throw Status.PERMISSION_DENIED.withDescription("caller not allowed").asRuntimeException();
    }
  }
}
