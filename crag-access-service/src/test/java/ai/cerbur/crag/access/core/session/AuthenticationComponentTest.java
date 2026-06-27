package ai.cerbur.crag.access.core.session;

import static org.junit.jupiter.api.Assertions.*;

import ai.cerbur.crag.access.core.identity.InvalidCredentialsException;
import ai.cerbur.crag.access.core.identity.RegisterIdentityCommand;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 认证 Facade 轻量组件测试（H2 + 真实 RSA 密钥）。
 *
 * <p>非事务：每个用例独立提交，使 Refresh 复用检测的 REQUIRES_NEW 撤销能读到已提交的 Session 行。用唯一 Username 隔离数据。 真实 PostgreSQL
 * 并发由 Docker 回归证明。
 */
@SpringBootTest
class AuthenticationComponentTest {

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
  @DisplayName("注册签发 Access JWT 与 Refresh Token")
  void registerIssuesTokens() {
    AuthenticationResult result = registerUnique();
    assertEquals(3, result.tokens().accessToken().split("\\.").length);
    assertFalse(result.tokens().refreshToken().isBlank());
    assertTrue(result.tokens().sessionFamilyId() > 0);
    assertTrue(result.tokens().refreshExpiresAt().isAfter(result.tokens().accessExpiresAt()));
  }

  @Test
  @DisplayName("登录签发新 Session Family 的 Token")
  void loginIssuesTokens() {
    String username = uniqueUsername();
    authenticationService.register(
        new RegisterIdentityCommand("Nick", username, PASSWORD.toCharArray()));
    AuthenticationResult login = authenticationService.login(username, PASSWORD.toCharArray());
    assertEquals(3, login.tokens().accessToken().split("\\.").length);
    assertFalse(login.tokens().refreshToken().isBlank());
  }

  @Test
  @DisplayName("刷新轮换 Token；旧 Token 复用撤销 Family，新 Token 随后失效")
  void refreshRotatesAndReuseRevokesFamily() {
    AuthenticationResult registered = registerUnique();
    String firstRefresh = registered.tokens().refreshToken();

    AuthenticationResult rotated = authenticationService.refresh(firstRefresh);
    assertNotEquals(firstRefresh, rotated.tokens().refreshToken());

    assertThrows(
        InvalidRefreshTokenException.class, () -> authenticationService.refresh(firstRefresh));
    assertThrows(
        InvalidRefreshTokenException.class,
        () -> authenticationService.refresh(rotated.tokens().refreshToken()));
  }

  @Test
  @DisplayName("Logout 撤销当前 Family，后续刷新失败")
  void logoutRevokesFamily() {
    AuthenticationResult registered = registerUnique();
    authenticationService.logout(registered.userId(), registered.tokens().sessionFamilyId());
    assertThrows(
        InvalidRefreshTokenException.class,
        () -> authenticationService.refresh(registered.tokens().refreshToken()));
  }

  @Test
  @DisplayName("错误密码登录抛 InvalidCredentialsException")
  void loginWrongPassword() {
    String username = uniqueUsername();
    authenticationService.register(
        new RegisterIdentityCommand("Nick", username, PASSWORD.toCharArray()));
    assertThrows(
        InvalidCredentialsException.class,
        () -> authenticationService.login(username, "wrong-password-12345".toCharArray()));
  }
}
