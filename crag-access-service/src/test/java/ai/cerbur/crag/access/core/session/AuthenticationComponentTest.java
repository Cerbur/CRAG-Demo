package ai.cerbur.crag.access.core.session;

import static org.junit.jupiter.api.Assertions.*;

import ai.cerbur.crag.access.core.identity.RegisterIdentityCommand;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * 认证 Facade 轻量组件测试（H2 + 真实 RSA 密钥）。
 *
 * <p>验证注册/登录签发 Token、Refresh 轮换、旧 Token 复用撤销 Family 与 Logout。真实 PostgreSQL 并发由 Docker 回归证明。
 */
@SpringBootTest
@Transactional
class AuthenticationComponentTest {

  private static final String PASSWORD = "correct-horse-battery-12";

  @Autowired private AuthenticationService authenticationService;
  @Autowired private EntityManager entityManager;

  @Test
  @DisplayName("注册签发 Access JWT 与 Refresh Token")
  void registerIssuesTokens() {
    AuthenticationResult result =
        authenticationService.register(
            new RegisterIdentityCommand("Alice", "alice", PASSWORD.toCharArray()));
    entityManager.flush();
    assertEquals(3, result.tokens().accessToken().split("\\.").length);
    assertFalse(result.tokens().refreshToken().isBlank());
    assertTrue(result.tokens().sessionFamilyId() > 0);
    assertTrue(result.tokens().refreshExpiresAt().isAfter(result.tokens().accessExpiresAt()));
  }

  @Test
  @DisplayName("登录签发新 Session Family 的 Token")
  void loginIssuesTokens() {
    authenticationService.register(
        new RegisterIdentityCommand("Alice", "alice", PASSWORD.toCharArray()));
    entityManager.flush();
    AuthenticationResult login = authenticationService.login("alice", PASSWORD.toCharArray());
    assertEquals(3, login.tokens().accessToken().split("\\.").length);
    assertFalse(login.tokens().refreshToken().isBlank());
  }

  @Test
  @DisplayName("刷新轮换 Token；旧 Token 复用撤销 Family，新 Token 随后失效")
  void refreshRotatesAndReuseRevokesFamily() {
    AuthenticationResult registered =
        authenticationService.register(
            new RegisterIdentityCommand("Alice", "alice", PASSWORD.toCharArray()));
    entityManager.flush();
    String firstRefresh = registered.tokens().refreshToken();

    AuthenticationResult rotated = authenticationService.refresh(firstRefresh);
    entityManager.flush();
    assertNotEquals(firstRefresh, rotated.tokens().refreshToken());

    // 旧 Token 复用 → 撤销整个 Family
    assertThrows(
        InvalidRefreshTokenException.class, () -> authenticationService.refresh(firstRefresh));
    // Family 已撤销，新 Token 也失效
    assertThrows(
        InvalidRefreshTokenException.class,
        () -> authenticationService.refresh(rotated.tokens().refreshToken()));
  }

  @Test
  @DisplayName("Logout 撤销当前 Family，后续刷新失败")
  void logoutRevokesFamily() {
    AuthenticationResult registered =
        authenticationService.register(
            new RegisterIdentityCommand("Bob", "bob", PASSWORD.toCharArray()));
    entityManager.flush();
    authenticationService.logout(registered.userId(), registered.tokens().sessionFamilyId());
    entityManager.flush();
    assertThrows(
        InvalidRefreshTokenException.class,
        () -> authenticationService.refresh(registered.tokens().refreshToken()));
  }

  @Test
  @DisplayName("错误密码登录抛 InvalidCredentialsException")
  void loginWrongPassword() {
    authenticationService.register(
        new RegisterIdentityCommand("Alice", "alice", PASSWORD.toCharArray()));
    entityManager.flush();
    assertThrows(
        ai.cerbur.crag.access.core.identity.InvalidCredentialsException.class,
        () -> authenticationService.login("alice", "wrong-password-12345".toCharArray()));
  }
}
