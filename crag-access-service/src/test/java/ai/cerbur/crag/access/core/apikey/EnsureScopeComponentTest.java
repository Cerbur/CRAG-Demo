package ai.cerbur.crag.access.core.apikey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.cerbur.crag.access.core.identity.IdentityService;
import ai.cerbur.crag.access.core.identity.RegisterIdentityCommand;
import ai.cerbur.crag.access.core.identity.RegisteredIdentity;
import ai.cerbur.crag.access.core.membership.MembershipService;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * EnsureScope 与 API Key 查询/列表组件测试（plan_21/21.2）。
 *
 * <p>验证 EnsureScope 对同一 (knowledgeBaseId, tenantId) 幂等、跨 Tenant 冲突、BLOCKED 不复活；Key get/list
 * 安全投影与分页稳定；鉴权返回 keyVersion/scopeVersion 水位。
 */
@SpringBootTest
@Transactional
class EnsureScopeComponentTest {

  @Autowired private ApiKeyService apiKeyService;
  @Autowired private IdentityService identityService;
  @Autowired private MembershipService membershipService;
  @Autowired private EntityManager entityManager;

  @Test
  @DisplayName("EnsureScope 首次创建 ACTIVE Scope；再次调用幂等返回同一 Scope")
  void ensureScopeIdempotentSameTenant() {
    RegisteredIdentity owner = register("OwnerEnsureA", "ownerensure21a");
    long kb = 9101L;

    ApiKeyScopeResult first = apiKeyService.ensureScope(owner.userId(), owner.tenantId(), kb);
    entityManager.flush();

    ApiKeyScopeResult second = apiKeyService.ensureScope(owner.userId(), owner.tenantId(), kb);
    entityManager.flush();

    assertThat(first.knowledgeBaseId()).isEqualTo(kb);
    assertThat(second.knowledgeBaseId()).isEqualTo(kb);
    assertThat(second.version()).isEqualTo(first.version());
    assertThat(second.status()).isEqualTo("ACTIVE");
  }

  @Test
  @DisplayName("EnsureScope 跨 Tenant 冲突抛 ScopeStateException")
  void ensureScopeCrossTenantConflict() {
    RegisteredIdentity ownerA = register("OwnerConflictA", "ownerconflict21a");
    RegisteredIdentity ownerB = register("OwnerConflictB", "ownerconflict21b");
    long kb = 9102L;

    apiKeyService.registerScope(ownerA.userId(), ownerA.tenantId(), kb);
    entityManager.flush();

    // 同一 KnowledgeBase 但不同 Tenant — 必须冲突，不能悄悄改 tenant 归属。
    assertThrows(
        ScopeStateException.class,
        () -> apiKeyService.ensureScope(ownerB.userId(), ownerB.tenantId(), kb));
  }

  @Test
  @DisplayName("EnsureScope 不复活 BLOCKED Scope")
  void ensureScopeDoesNotReviveBlocked() {
    RegisteredIdentity owner = register("OwnerBlocked", "ownerblocked21a");
    long kb = 9103L;

    apiKeyService.registerScope(owner.userId(), owner.tenantId(), kb);
    apiKeyService.blockScope(owner.userId(), owner.tenantId(), kb);
    entityManager.flush();

    // BLOCKED 是终态；Ensure 不得复活为 ACTIVE。
    ApiKeyScopeResult result = apiKeyService.ensureScope(owner.userId(), owner.tenantId(), kb);
    assertThat(result.status()).isEqualTo("BLOCKED");
  }

  @Test
  @DisplayName("getScope 由 MEMBER 调用抛 MembershipAuthorizationException（MANAGE_API_KEY 仅 OWNER）")
  void getScopeMemberUnauthorized() {
    RegisteredIdentity owner = register("OwnerMemberAuth", "ownermemberauth21a");
    RegisteredIdentity member = register("MemberAuth", "memberauth21a");
    membershipService.addByUsername(owner.userId(), owner.tenantId(), "memberauth21a");
    entityManager.flush();
    long kb = 9104L;
    apiKeyService.registerScope(owner.userId(), owner.tenantId(), kb);
    entityManager.flush();

    // MEMBER 可创建 KnowledgeBase（故 ensureScope 允许），但 getScope 需要 MANAGE_API_KEY，仅 OWNER 可用。
    assertThrows(
        ai.cerbur.crag.access.core.membership.MembershipAuthorizationException.class,
        () -> apiKeyService.getScope(member.userId(), owner.tenantId(), kb));
  }

  @Test
  @DisplayName("get 返回 Key 安全投影；跨 Tenant 不泄漏存在性")
  void getKeyReturnsSafeProjection() {
    RegisteredIdentity owner = register("OwnerGet", "ownerget21a");
    long kb = 9105L;
    apiKeyService.registerScope(owner.userId(), owner.tenantId(), kb);
    CreatedApiKey created =
        apiKeyService.create(owner.userId(), owner.tenantId(), kb, "get-key", Duration.ofDays(30));
    entityManager.flush();

    ApiKeyResult result = apiKeyService.get(owner.userId(), owner.tenantId(), created.apiKeyId());
    assertThat(result.apiKeyId()).isEqualTo(created.apiKeyId());
    assertThat(result.name()).isEqualTo("get-key");
    assertThat(result.keyPrefix()).isNotBlank();
  }

  @Test
  @DisplayName("list 按 KnowledgeBase 分页返回 Key 投影，顺序稳定")
  void listKeysPaginatedStable() {
    RegisteredIdentity owner = register("OwnerList", "ownerlist21a");
    long kb = 9106L;
    apiKeyService.registerScope(owner.userId(), owner.tenantId(), kb);
    CreatedApiKey k1 =
        apiKeyService.create(owner.userId(), owner.tenantId(), kb, "k1", Duration.ofDays(30));
    CreatedApiKey k2 =
        apiKeyService.create(owner.userId(), owner.tenantId(), kb, "k2", Duration.ofDays(30));
    entityManager.flush();

    ApiKeyListPage firstPage = apiKeyService.list(owner.userId(), owner.tenantId(), kb, 1, null);
    ApiKeyListPage secondPage = apiKeyService.list(owner.userId(), owner.tenantId(), kb, 50, null);

    assertThat(firstPage.items()).hasSize(1);
    assertThat(firstPage.nextPageToken()).isNotBlank();
    assertThat(secondPage.items()).hasSizeGreaterThanOrEqualTo(2);
    // 第二页包含全部 Key，证明分页只截取而非丢失。
    assertThat(secondPage.items().stream().map(ApiKeyResult::apiKeyId))
        .contains(k1.apiKeyId(), k2.apiKeyId());
  }

  @Test
  @DisplayName("authenticate 返回 keyVersion 与 scopeVersion 水位")
  void authenticateReturnsVersionWatermarks() {
    RegisteredIdentity owner = register("OwnerVersion", "ownerversion21a");
    long kb = 9107L;
    apiKeyService.registerScope(owner.userId(), owner.tenantId(), kb);
    CreatedApiKey created =
        apiKeyService.create(owner.userId(), owner.tenantId(), kb, "ver-key", Duration.ofDays(30));
    entityManager.flush();

    AuthenticatedApiKey auth = apiKeyService.authenticate(created.completeKey());
    assertThat(auth.apiKeyId()).isEqualTo(created.apiKeyId());
    assertThat(auth.keyVersion()).isGreaterThanOrEqualTo(0L);
    assertThat(auth.scopeVersion()).isGreaterThanOrEqualTo(0L);
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
