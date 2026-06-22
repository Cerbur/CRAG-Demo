package ai.cerbur.crag.grpc.runtime;

import static org.junit.jupiter.api.Assertions.*;

import ai.cerbur.crag.grpc.runtime.identity.GrpcCallerIdentity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GrpcCallerIdentityTest {

  @Test
  @DisplayName("创建合法身份")
  void create_valid() {
    GrpcCallerIdentity identity = new GrpcCallerIdentity("test-service");
    assertEquals("test-service", identity.serviceName());
  }

  @Test
  @DisplayName("serviceName 为 null 时抛出异常")
  void create_nullServiceName_throws() {
    assertThrows(IllegalArgumentException.class, () -> new GrpcCallerIdentity(null));
  }

  @Test
  @DisplayName("serviceName 为空白时抛出异常")
  void create_blankServiceName_throws() {
    assertThrows(IllegalArgumentException.class, () -> new GrpcCallerIdentity("  "));
  }

  @Test
  @DisplayName("相同 serviceName 的身份相等")
  void equality() {
    GrpcCallerIdentity a = new GrpcCallerIdentity("svc");
    GrpcCallerIdentity b = new GrpcCallerIdentity("svc");
    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
  }
}
