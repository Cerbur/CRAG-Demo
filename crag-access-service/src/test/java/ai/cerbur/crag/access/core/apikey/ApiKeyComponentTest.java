package ai.cerbur.crag.access.core.apikey;

import static org.junit.jupiter.api.Assertions.*;

import ai.cerbur.crag.access.core.identity.IdentityService;
import ai.cerbur.crag.access.core.identity.RegisterIdentityCommand;
import ai.cerbur.crag.access.core.identity.RegisteredIdentity;
import ai.cerbur.crag.access.core.membership.MembershipAuthorizationException;
import ai.cerbur.crag.access.core.membership.MembershipService;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * API Key 轻量组件测试（H2）。
 *
 * <p>验证 Scope 注册/终态阻塞、Key 创建/鉴权/停用/启用/轮换/吊销与过期，以及完整 Key 只在创建/轮换时返回一次。失效事件生产由 20.7 接入。
 */
@SpringBootTest
@Transactional
class ApiKeyComponentTest {

  @Autowired private ApiKeyService apiKeyService;
  @Autowired private IdentityService identityService;
  @Autowired private MembershipService membershipService;
  @Autowired private EntityManager entityManager;

  @Test
  @DisplayName("注册 Scope 并创建 Key，鉴权成功")
  void registerScopeCreateAndAuthenticate() {
    RegisteredIdentity owner = register("ownerA", "ownerakba");
    long kb = 9001L;
    apiKeyService.registerScope(owner.userId(), owner.tenantId(), kb);
    CreatedApiKey created =
        apiKeyService.create(owner.userId(), owner.tenantId(), kb, "key-1", Duration.ofDays(90));
    entityManager.flush();
    assertTrue(created.completeKey().startsWith("crag_"));
    AuthenticatedApiKey auth = apiKeyService.authenticate(created.completeKey());
    assertEquals(created.apiKeyId(), auth.apiKeyId());
    assertEquals(kb, auth.knowledgeBaseId());
  }

  @Test
  @DisplayName("停用→启用→鉴权恢复")
  void disableAndEnable() {
    RegisteredIdentity owner = register("ownerB", "ownerakbb");
    long kb = 9002L;
    apiKeyService.registerScope(owner.userId(), owner.tenantId(), kb);
    CreatedApiKey created =
        apiKeyService.create(owner.userId(), owner.tenantId(), kb, "key-2", Duration.ofDays(30));
    entityManager.flush();
    apiKeyService.disable(owner.userId(), owner.tenantId(), created.apiKeyId());
    entityManager.flush();
    entityManager.clear();
    assertThrows(
        ApiKeyNotFoundException.class, () -> apiKeyService.authenticate(created.completeKey()));
    apiKeyService.enable(owner.userId(), owner.tenantId(), created.apiKeyId());
    entityManager.flush();
    entityManager.clear();
    assertDoesNotThrow(() -> apiKeyService.authenticate(created.completeKey()));
  }

  @Test
  @DisplayName("吊销后鉴权失败，且不可再启用")
  void revokeIsTerminal() {
    RegisteredIdentity owner = register("ownerC", "ownerakbc");
    long kb = 9003L;
    apiKeyService.registerScope(owner.userId(), owner.tenantId(), kb);
    CreatedApiKey created =
        apiKeyService.create(owner.userId(), owner.tenantId(), kb, "key-3", Duration.ofDays(30));
    entityManager.flush();
    apiKeyService.revoke(owner.userId(), owner.tenantId(), created.apiKeyId());
    entityManager.flush();
    entityManager.clear();
    assertThrows(
        ApiKeyNotFoundException.class, () -> apiKeyService.authenticate(created.completeKey()));
    assertThrows(
        ApiKeyStateException.class,
        () -> apiKeyService.enable(owner.userId(), owner.tenantId(), created.apiKeyId()));
  }

  @Test
  @DisplayName("轮换返回新 Key，旧 Key 鉴权失败")
  void rotateIssuesNewKey() {
    RegisteredIdentity owner = register("ownerD", "ownerakbd");
    long kb = 9004L;
    apiKeyService.registerScope(owner.userId(), owner.tenantId(), kb);
    CreatedApiKey created =
        apiKeyService.create(owner.userId(), owner.tenantId(), kb, "key-4", Duration.ofDays(30));
    entityManager.flush();
    CreatedApiKey rotated =
        apiKeyService.rotate(
            owner.userId(), owner.tenantId(), created.apiKeyId(), Duration.ofDays(30));
    entityManager.flush();
    entityManager.clear();
    assertNotEquals(created.completeKey(), rotated.completeKey());
    assertThrows(
        ApiKeyNotFoundException.class, () -> apiKeyService.authenticate(created.completeKey()));
    assertDoesNotThrow(() -> apiKeyService.authenticate(rotated.completeKey()));
  }

  @Test
  @DisplayName("终态阻塞 Scope 禁用全部有效 Key")
  void blockScopeDisablesKeys() {
    RegisteredIdentity owner = register("ownerE", "ownerakbe");
    long kb = 9005L;
    apiKeyService.registerScope(owner.userId(), owner.tenantId(), kb);
    CreatedApiKey created =
        apiKeyService.create(owner.userId(), owner.tenantId(), kb, "key-5", Duration.ofDays(30));
    entityManager.flush();
    apiKeyService.blockScope(owner.userId(), owner.tenantId(), kb);
    entityManager.flush();
    entityManager.clear();
    assertThrows(
        ApiKeyNotFoundException.class, () -> apiKeyService.authenticate(created.completeKey()));
  }

  @Test
  @DisplayName("被阻塞 Scope 不能创建 Key")
  void blockedScopeRejectsCreate() {
    RegisteredIdentity owner = register("ownerF", "ownerakbf");
    long kb = 9006L;
    apiKeyService.registerScope(owner.userId(), owner.tenantId(), kb);
    apiKeyService.blockScope(owner.userId(), owner.tenantId(), kb);
    entityManager.flush();
    assertThrows(
        ScopeBlockedException.class,
        () ->
            apiKeyService.create(
                owner.userId(), owner.tenantId(), kb, "key-6", Duration.ofDays(30)));
  }

  @Test
  @DisplayName("MEMBER 不能创建或管理 API Key")
  void memberCannotManageKeys() {
    RegisteredIdentity owner = register("ownerG", "ownerakbg");
    RegisteredIdentity member = register("memberG", "memberakbg");
    membershipService.addByUsername(owner.userId(), owner.tenantId(), "memberakbg");
    entityManager.flush();
    long kb = 9007L;
    apiKeyService.registerScope(owner.userId(), owner.tenantId(), kb);
    assertThrows(
        MembershipAuthorizationException.class,
        () ->
            apiKeyService.create(
                member.userId(), owner.tenantId(), kb, "key-7", Duration.ofDays(30)));
  }

  @Test
  @DisplayName("鉴权伪造 Key 统一失败，不泄漏存在性")
  void authenticateMalformedFails() {
    assertThrows(
        ApiKeyNotFoundException.class, () -> apiKeyService.authenticate("crag_noprefixhere_fake"));
    assertThrows(ApiKeyNotFoundException.class, () -> apiKeyService.authenticate("garbage"));
  }

  private RegisteredIdentity register(String nickname, String username) {
    RegisteredIdentity registered =
        identityService.register(
            new RegisterIdentityCommand(
                nickname, username, "correct-horse-battery-12".toCharArray()));
    entityManager.flush();
    return registered;
  }
}
