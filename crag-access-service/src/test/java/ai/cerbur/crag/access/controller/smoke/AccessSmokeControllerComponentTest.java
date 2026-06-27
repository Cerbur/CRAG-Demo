package ai.cerbur.crag.access.controller.smoke;

import static org.junit.jupiter.api.Assertions.*;

import ai.cerbur.crag.access.controller.smoke.dto.AccessSmokeRequests;
import ai.cerbur.crag.access.controller.smoke.dto.AccessSmokeResponses;
import ai.cerbur.crag.common.dto.result.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Access smoke HTTP 入口轻量组件测试（smoke Profile）。
 *
 * <p>验证 smoke Controller 在 smoke Profile 下装配并复用 Core 用例服务返回 Token；真实 HTTP/PostgreSQL/Redis 链路由
 * Docker 回归证明。
 */
@SpringBootTest(properties = "spring.profiles.active=smoke")
@ActiveProfiles("smoke")
class AccessSmokeControllerComponentTest {

  @Autowired private AccessSmokeController controller;

  @Test
  @DisplayName("smoke Profile 装配 Controller 并返回 Token")
  void smokeControllerIssuesTokens() {
    Response<AccessSmokeResponses.AuthResponse> result =
        controller.register(
            new AccessSmokeRequests.RegisterRequest(
                "Alice", "alicesmoke", "correct-horse-battery-12"));
    assertTrue(result.isSuccess());
    assertNotNull(result.getResult().accessToken());
    assertNotNull(result.getResult().refreshToken());
  }

  @Test
  @DisplayName("JWT 公钥端点返回非空公钥集")
  void jwtKeysExposed() {
    Response<AccessSmokeResponses.JwtKeysResponse> keys = controller.jwtKeys();
    assertTrue(keys.isSuccess());
    assertFalse(keys.getResult().keys().isEmpty());
    assertNotNull(keys.getResult().keys().get(0).publicKeyPem());
  }
}
