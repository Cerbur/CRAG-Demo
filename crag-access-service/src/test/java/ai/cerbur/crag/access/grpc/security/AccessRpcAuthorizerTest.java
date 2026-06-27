package ai.cerbur.crag.access.grpc.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ai.cerbur.crag.grpc.runtime.identity.GrpcCallerContext;
import ai.cerbur.crag.grpc.runtime.identity.GrpcCallerIdentity;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** AccessRpcAuthorizer 纯单元测试：Console/Open API 调用方矩阵。 */
@ExtendWith(MockitoExtension.class)
class AccessRpcAuthorizerTest {

  @Mock private GrpcCallerContext callerContext;

  @InjectMocks private AccessRpcAuthorizer authorizer;

  @Test
  @DisplayName("requireConsole 在 console-api 调用方下通过")
  void consoleAllowed() {
    when(callerContext.requireIdentity()).thenReturn(new GrpcCallerIdentity("console-api"));
    assertDoesNotThrow(() -> authorizer.requireConsole());
  }

  @Test
  @DisplayName("requireConsole 在 open-api 调用方下拒绝")
  void consoleRejectsOpenApi() {
    when(callerContext.requireIdentity()).thenReturn(new GrpcCallerIdentity("open-api"));
    assertThrows(StatusRuntimeException.class, () -> authorizer.requireConsole());
  }

  @Test
  @DisplayName("requireOpenApi 在 open-api 调用方下通过")
  void openApiAllowed() {
    when(callerContext.requireIdentity()).thenReturn(new GrpcCallerIdentity("open-api"));
    assertDoesNotThrow(() -> authorizer.requireOpenApi());
  }

  @Test
  @DisplayName("requireConsoleOrOpenApi 接受 console 与 open，拒绝其他服务")
  void consoleOrOpenApi() {
    when(callerContext.requireIdentity()).thenReturn(new GrpcCallerIdentity("console-api"));
    assertDoesNotThrow(() -> authorizer.requireConsoleOrOpenApi());
    when(callerContext.requireIdentity()).thenReturn(new GrpcCallerIdentity("open-api"));
    assertDoesNotThrow(() -> authorizer.requireConsoleOrOpenApi());
    when(callerContext.requireIdentity()).thenReturn(new GrpcCallerIdentity("rag-service"));
    assertThrows(StatusRuntimeException.class, () -> authorizer.requireConsoleOrOpenApi());
  }
}
