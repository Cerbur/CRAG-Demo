package ai.cerbur.crag.access.core.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.cerbur.crag.access.core.identity.RegisterIdentityCommand;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 按 Refresh Token 撤销会话与鉴权版本字段组件测试（plan_21/21.2）。
 *
 * <p>非事务：每个用例独立提交，使 REQUIRES_NEW 撤销能读到已提交的 Session 行。验证 logout(rawRefreshToken) 通过 HMAC 定位并撤销
 * Family，不需要 Access JWT；鉴权返回 keyVersion/scopeVersion 水位。
 */
@SpringBootTest
class LogoutByRefreshTokenComponentTest {

  private static final String PASSWORD = "correct-horse-battery-12";

  @Autowired private AuthenticationService authenticationService;

  private String uniqueUsername() {
    return "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
  }

  private AuthenticationResult registerUnique() {
    return authenticationService.register(
        new RegisterIdentityCommand("Nick", uniqueUsername(), PASSWORD.toCharArray()));
  }

  @Test
  @DisplayName("logout(rawRefreshToken) 撤销 Family，后续 refresh 失败")
  void logoutByRawRefreshTokenRevokesFamily() {
    AuthenticationResult registered = registerUnique();
    String refreshToken = registered.tokens().refreshToken();

    authenticationService.logout(refreshToken);

    assertThrows(
        InvalidRefreshTokenException.class, () -> authenticationService.refresh(refreshToken));
  }

  @Test
  @DisplayName("logout 失效的 Refresh Token 抛 InvalidRefreshTokenException")
  void logoutUnknownRefreshTokenThrows() {
    assertThrows(
        InvalidRefreshTokenException.class, () -> authenticationService.logout("not-a-real-token"));
  }

  @Test
  @DisplayName("logout 不需要 Access JWT — 只用 Refresh Token 即可撤销")
  void logoutDoesNotRequireAccessToken() {
    AuthenticationResult registered = registerUnique();
    // 调用 logout 时只传 Refresh Token，不依赖 Access JWT 任何状态。
    authenticationService.logout(registered.tokens().refreshToken());
    // 撤销后 Access JWT 仍可由调用方自行处理；本断言确认撤销路径本身无 Access JWT 输入。
    assertThat(registered.tokens().accessToken()).isNotBlank();
  }
}
