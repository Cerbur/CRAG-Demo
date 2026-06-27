package ai.cerbur.crag.access.dao;

import static org.junit.jupiter.api.Assertions.*;

import ai.cerbur.crag.access.dao.entity.*;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.Optional;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * Access DAO 轻量组件测试（H2 + 真实 schema-access.sql）。
 *
 * <p>验证实体映射、唯一约束、版本 CAS 抢占与悲观锁查询装配。H2 不表述 PostgreSQL 锁语义；真实并发与最后 OWNER 保护由 Docker HTTP 回归证明。
 */
@SpringBootTest
@Transactional
class AccessDaoComponentTest {

  @Autowired private PlatformUserDao userDao;
  @Autowired private LoginAccountDao accountDao;
  @Autowired private TenantDao tenantDao;
  @Autowired private TenantMembershipDao membershipDao;
  @Autowired private RefreshSessionDao sessionDao;
  @Autowired private ApiKeyScopeDao scopeDao;
  @Autowired private ApiKeyDao apiKeyDao;
  @Autowired private EntityManager entityManager;

  @Test
  @DisplayName("插入并按规范化 Username 查询账号")
  void accountInsertAndFindByNormalizedUsername() {
    PlatformUserEntity user = userDao.insert(PlatformUserEntity.create(1L, "Alice"));
    accountDao.insert(
        LoginAccountEntity.create(11L, user.getUserId(), "Alice", "alice", "argon2-hash"));

    entityManager.flush();
    Optional<LoginAccountEntity> found = accountDao.findByNormalizedUsername("alice");
    assertTrue(found.isPresent());
    assertEquals(user.getUserId(), found.get().getUserId());
  }

  @Test
  @DisplayName("规范化 Username 全局唯一约束兜底")
  void accountNormalizedUnique() {
    PlatformUserEntity user = userDao.insert(PlatformUserEntity.create(2L, "Bob"));
    accountDao.insert(
        LoginAccountEntity.create(12L, user.getUserId(), "Bob", "bob", "argon2-hash"));
    entityManager.flush();
    assertThrows(
        ConstraintViolationException.class,
        () -> {
          accountDao.insert(LoginAccountEntity.create(13L, 999L, "Bob2", "bob", "argon2-hash"));
          entityManager.flush();
        });
  }

  @Test
  @DisplayName("成员关系 (tenant, user) 唯一约束兜底")
  void membershipTenantUserUnique() {
    membershipDao.insert(TenantMembershipEntity.createOwner(21L, 200L, 2L));
    entityManager.flush();
    assertThrows(
        ConstraintViolationException.class,
        () -> {
          membershipDao.insert(TenantMembershipEntity.createOwner(22L, 200L, 2L));
          entityManager.flush();
        });
  }

  @Test
  @DisplayName("成员角色/状态版本 CAS 成功推进并递增版本")
  void membershipCasSuccess() {
    TenantMembershipEntity membership =
        membershipDao.insert(TenantMembershipEntity.createOwner(31L, 300L, 3L));
    entityManager.flush();
    long before = membership.getVersion();
    TenantMembershipEntity updated =
        membershipDao.updateRoleAndStatus(300L, 3L, "MEMBER", "ACTIVE", before);
    assertEquals(before + 1, updated.getVersion());
    assertEquals("MEMBER", updated.getRole());
  }

  @Test
  @DisplayName("成员版本 CAS 抢占失败抛 VersionConflictException")
  void membershipCasConflict() {
    membershipDao.insert(TenantMembershipEntity.createOwner(41L, 400L, 4L));
    entityManager.flush();
    assertThrows(
        VersionConflictException.class,
        () -> membershipDao.updateRoleAndStatus(400L, 4L, "MEMBER", "ACTIVE", 999L));
  }

  @Test
  @DisplayName("Refresh 会话轮换版本 CAS 成功，记录替代会话")
  void refreshRotateSuccess() {
    LocalDateTime now = LocalDateTime.now();
    sessionDao.insert(RefreshSessionEntity.create(51L, 500L, 5L, "hmac-1", now, now.plusDays(30)));
    entityManager.flush();
    RefreshSessionEntity rotated = sessionDao.rotate(51L, 0L, 52L);
    assertEquals(RefreshSessionEntity.STATUS_ROTATED, rotated.getStatus());
    assertEquals(52L, rotated.getReplacedBy());
  }

  @Test
  @DisplayName("Refresh 轮换版本 CAS 抢占失败抛 VersionConflictException")
  void refreshRotateConflict() {
    LocalDateTime now = LocalDateTime.now();
    sessionDao.insert(RefreshSessionEntity.create(61L, 600L, 6L, "hmac-2", now, now.plusDays(30)));
    entityManager.flush();
    assertThrows(VersionConflictException.class, () -> sessionDao.rotate(61L, 999L, 62L));
  }

  @Test
  @DisplayName("Family 撤销批量更新 ACTIVE 与 ROTATED 会话")
  void refreshRevokeFamily() {
    LocalDateTime now = LocalDateTime.now();
    sessionDao.insert(RefreshSessionEntity.create(71L, 700L, 7L, "h1", now, now.plusDays(30)));
    sessionDao.insert(RefreshSessionEntity.create(72L, 700L, 7L, "h2", now, now.plusDays(30)));
    entityManager.flush();
    int affected = sessionDao.revokeFamily(700L);
    assertEquals(2, affected);
  }

  @Test
  @DisplayName("Scope 终态阻塞版本 CAS 成功")
  void scopeBlockSuccess() {
    scopeDao.insert(ApiKeyScopeEntity.create(800L, 8L));
    entityManager.flush();
    assertDoesNotThrow(() -> scopeDao.block(800L, 0L));
    assertEquals(
        ApiKeyScopeEntity.STATUS_BLOCKED,
        scopeDao.findByKnowledgeBase(800L).orElseThrow().getStatus());
  }

  @Test
  @DisplayName("API Key 状态版本 CAS 成功并按前缀查询")
  void apiKeyStatusCasAndFindByPrefix() {
    LocalDateTime now = LocalDateTime.now();
    apiKeyDao.insert(
        ApiKeyEntity.create(
            91L, 9L, 800L, "key-1", "crag_prefix1", "secret-hmac", 9L, now.plusDays(90)));
    entityManager.flush();
    assertTrue(apiKeyDao.findByPrefix("crag_prefix1").isPresent());
    ApiKeyEntity updated = apiKeyDao.updateStatus(91L, 0L, ApiKeyEntity.STATUS_DISABLED, now, null);
    assertEquals(ApiKeyEntity.STATUS_DISABLED, updated.getStatus());
  }

  @Test
  @DisplayName("Scope Block 批量禁用 KnowledgeBase 内全部 ACTIVE Key")
  void apiKeyBulkDisableByKnowledgeBase() {
    LocalDateTime now = LocalDateTime.now();
    apiKeyDao.insert(
        ApiKeyEntity.create(101L, 10L, 801L, "k-a", "pa", "sa", 10L, now.plusDays(90)));
    apiKeyDao.insert(
        ApiKeyEntity.create(102L, 10L, 801L, "k-b", "pb", "sb", 10L, now.plusDays(90)));
    entityManager.flush();
    int affected = apiKeyDao.disableActiveByKnowledgeBase(801L);
    assertEquals(2, affected);
  }

  @Test
  @DisplayName("Tenant 悲观锁查询装配可用")
  void tenantFindForUpdateLoads() {
    tenantDao.insert(TenantEntity.create(1000L, "tenant-1"));
    entityManager.flush();
    assertTrue(tenantDao.findForUpdate(1000L).isPresent());
  }
}
