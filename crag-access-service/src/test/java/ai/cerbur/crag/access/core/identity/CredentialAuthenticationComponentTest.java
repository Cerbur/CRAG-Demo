package ai.cerbur.crag.access.core.identity;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * 登录认证轻量组件测试（H2）。
 *
 * <p>验证正确凭据返回身份摘要，错误密码、不存在 Username 与禁用状态统一抛 {@link InvalidCredentialsException}，不区分原因。
 */
@SpringBootTest
@Transactional
class CredentialAuthenticationComponentTest {

  private static final String PASSWORD = "correct-horse-battery-12";

  @Autowired private IdentityService identityService;
  @Autowired private EntityManager entityManager;

  @Test
  @DisplayName("正确凭据返回身份摘要")
  void authenticateSuccess() {
    identityService.register(new RegisterIdentityCommand("Alice", "alice", PASSWORD.toCharArray()));
    entityManager.flush();
    AuthenticatedIdentity identity = identityService.authenticate("alice", PASSWORD.toCharArray());
    assertEquals("Alice", identity.nickname());
  }

  @Test
  @DisplayName("错误密码与不存在 Username 抛同一异常类型，不泄漏存在性")
  void authenticateFailureUnified() {
    identityService.register(new RegisterIdentityCommand("Alice", "alice", PASSWORD.toCharArray()));
    entityManager.flush();
    Class<? extends Exception> wrongPassword =
        assertThrows(
                Exception.class,
                () -> identityService.authenticate("alice", "wrong-password-12345".toCharArray()))
            .getClass();
    Class<? extends Exception> missingUser =
        assertThrows(
                Exception.class,
                () -> identityService.authenticate("nobody", PASSWORD.toCharArray()))
            .getClass();
    assertEquals(InvalidCredentialsException.class, wrongPassword);
    assertEquals(InvalidCredentialsException.class, missingUser);
  }

  @Test
  @DisplayName("规范化 Username 大小写不敏感匹配")
  void authenticateCaseInsensitive() {
    identityService.register(new RegisterIdentityCommand("Alice", "alice", PASSWORD.toCharArray()));
    entityManager.flush();
    AuthenticatedIdentity identity =
        identityService.authenticate("  ALICE ", PASSWORD.toCharArray());
    assertEquals("Alice", identity.nickname());
  }

  @Test
  @DisplayName("账号被禁用后认证失败")
  void authenticateDisabledAccountFails() {
    RegisteredIdentity registered =
        identityService.register(new RegisterIdentityCommand("Bob", "bob", PASSWORD.toCharArray()));
    disableAccount(registered);
    assertThrows(
        InvalidCredentialsException.class,
        () -> identityService.authenticate("bob", PASSWORD.toCharArray()));
  }

  private void disableAccount(RegisteredIdentity registered) {
    entityManager
        .createNativeQuery("UPDATE login_account SET status = 'DISABLED' WHERE account_id = :id")
        .setParameter("id", registered.accountId())
        .executeUpdate();
    entityManager.flush();
    entityManager.clear();
  }
}
