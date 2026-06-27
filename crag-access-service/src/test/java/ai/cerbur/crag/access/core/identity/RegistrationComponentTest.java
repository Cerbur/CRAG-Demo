package ai.cerbur.crag.access.core.identity;

import static org.junit.jupiter.api.Assertions.*;

import ai.cerbur.crag.access.dao.LoginAccountDao;
import ai.cerbur.crag.access.dao.TenantDao;
import ai.cerbur.crag.access.dao.TenantMembershipDao;
import ai.cerbur.crag.access.dao.entity.LoginAccountEntity;
import ai.cerbur.crag.access.dao.entity.TenantEntity;
import ai.cerbur.crag.access.dao.entity.TenantMembershipEntity;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * 注册轻量组件测试（H2 + 真实 schema）。
 *
 * <p>验证注册事务原子创建 User、USERNAME Account、默认 Tenant 与 OWNER Membership；数据库只保存 Argon2id 哈希，不保存明文。
 */
@SpringBootTest
@Transactional
class RegistrationComponentTest {

  @Autowired private IdentityService identityService;
  @Autowired private LoginAccountDao accountDao;
  @Autowired private TenantDao tenantDao;
  @Autowired private TenantMembershipDao membershipDao;
  @Autowired private EntityManager entityManager;

  @Test
  @DisplayName("注册原子创建 User/Account/默认 Tenant/OWNER Membership")
  void registerCreatesAllEntities() {
    RegisteredIdentity registered =
        identityService.register(
            new RegisterIdentityCommand(
                "Alice", "alice", "correct-horse-battery-12".toCharArray()));

    entityManager.flush();
    assertTrue(registered.userId() > 0);
    assertTrue(registered.accountId() > 0);
    assertTrue(registered.tenantId() > 0);
    assertTrue(registered.membershipId() > 0);

    LoginAccountEntity account = accountDao.findByNormalizedUsername("alice").orElseThrow();
    assertEquals(registered.userId(), account.getUserId());
    // 只保存 Argon2id 哈希，不保存明文。
    assertNotEquals("correct-horse-battery-12", account.getCredentialHash());
    assertTrue(account.getCredentialHash().startsWith("$argon2id"));

    TenantEntity tenant = tenantDao.findById(registered.tenantId()).orElseThrow();
    assertEquals("Alice 的空间", tenant.getName());

    TenantMembershipEntity membership =
        membershipDao.findByTenantAndUser(registered.tenantId(), registered.userId()).orElseThrow();
    assertEquals(TenantMembershipEntity.ROLE_OWNER, membership.getRole());
    assertEquals(TenantMembershipEntity.STATUS_ACTIVE, membership.getStatus());
  }

  @Test
  @DisplayName("Username 冲突抛 UsernameConflictException")
  void registerUsernameConflict() {
    identityService.register(
        new RegisterIdentityCommand("Alice", "alice", "correct-horse-battery-12".toCharArray()));
    entityManager.flush();
    assertThrows(
        UsernameConflictException.class,
        () ->
            identityService.register(
                new RegisterIdentityCommand(
                    "Alice2", "ALICE", "another-strong-pw-12".toCharArray())));
  }

  @Test
  @DisplayName("非法输入抛 IllegalArgumentException 且不创建任何实体")
  void registerInvalidInput() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            identityService.register(
                new RegisterIdentityCommand("Al", "ab", "short".toCharArray())));
    entityManager.flush();
    assertTrue(accountDao.findByNormalizedUsername("ab").isEmpty());
  }

  @Test
  @DisplayName("密码 char[] 使用后被清零")
  void registerClearsPassword() {
    char[] password = "correct-horse-battery-12".toCharArray();
    identityService.register(new RegisterIdentityCommand("Bob", "bob", password));
    for (char c : password) {
      assertEquals('\0', c);
    }
  }
}
