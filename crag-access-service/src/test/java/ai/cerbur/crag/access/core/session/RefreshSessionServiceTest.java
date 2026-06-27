package ai.cerbur.crag.access.core.session;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ai.cerbur.crag.access.dao.LoginAccountDao;
import ai.cerbur.crag.access.dao.PlatformUserDao;
import ai.cerbur.crag.access.dao.RefreshSessionDao;
import ai.cerbur.crag.access.dao.entity.LoginAccountEntity;
import ai.cerbur.crag.access.dao.entity.PlatformUserEntity;
import ai.cerbur.crag.access.dao.entity.RefreshSessionEntity;
import ai.cerbur.crag.access.security.SecretGenerator;
import ai.cerbur.crag.access.security.SecretHmac;
import ai.cerbur.crag.id.api.CragIdGenerator;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

/** RefreshSessionService 纯单元测试：用 mock DAO 验证轮换状态机与复用检测。 */
@ExtendWith(MockitoExtension.class)
class RefreshSessionServiceTest {

  @Mock private RefreshSessionDao sessionDao;
  @Mock private PlatformUserDao userDao;
  @Mock private LoginAccountDao accountDao;
  @Mock private CragIdGenerator idGenerator;
  @Mock private SecretGenerator secretGenerator;
  @Mock private SecretHmac refreshHmac;
  @Mock private PlatformTransactionManager transactionManager;

  @InjectMocks private RefreshSessionService service;

  @BeforeEach
  void stubCommon() {
    AtomicLong counter = new AtomicLong(1000L);
    lenient().when(idGenerator.nextId(any())).thenReturn(counter.getAndIncrement());
    lenient().when(secretGenerator.randomBase64Url(anyInt())).thenReturn("secret");
    lenient().when(refreshHmac.digest(anyString())).thenReturn("hmac");
    // 复用撤销走 REQUIRES_NEW 事务模板：让事务管理器直接执行回调。
    lenient()
        .when(transactionManager.getTransaction(any()))
        .thenReturn(mock(TransactionStatus.class));
  }

  @Test
  @DisplayName("createNewFamily 写入一条 ACTIVE Session")
  void createNewFamilyInsertsActiveSession() {
    service.createNewFamily(7L);
    verify(sessionDao).insert(any(RefreshSessionEntity.class));
  }

  @Test
  @DisplayName("轮换 ACTIVE Token：标记 ROTATED 并写入替代会话")
  void rotateActiveToken() {
    RefreshSessionEntity active =
        RefreshSessionEntity.create(
            1L, 10L, 7L, "hmac", LocalDateTime.now(), LocalDateTime.now().plusDays(30));
    when(sessionDao.findByTokenHmac("hmac")).thenReturn(Optional.of(active));
    when(userDao.findById(7L)).thenReturn(Optional.of(activeUser()));
    when(accountDao.findByUserId(7L)).thenReturn(Optional.of(activeAccount()));

    RefreshSessionService.IssuedRefresh rotated = service.rotate("token");

    verify(sessionDao).rotate(eq(1L), eq(0L), anyLong());
    verify(sessionDao).insert(any(RefreshSessionEntity.class));
    assertEquals(10L, rotated.familyId());
    verify(sessionDao, never()).revokeFamily(anyLong());
  }

  @Test
  @DisplayName("ROTATED Token 再次出现：在独立事务撤销整个 Family 并拒绝")
  void rotateReusedRevokesFamily() {
    RefreshSessionEntity rotated =
        RefreshSessionEntity.create(
            1L, 10L, 7L, "hmac", LocalDateTime.now(), LocalDateTime.now().plusDays(30));
    org.springframework.test.util.ReflectionTestUtils.setField(rotated, "status", "ROTATED");
    when(sessionDao.findByTokenHmac("hmac")).thenReturn(Optional.of(rotated));

    assertThrows(InvalidRefreshTokenException.class, () -> service.rotate("token"));
    verify(sessionDao).revokeFamily(10L);
  }

  @Test
  @DisplayName("REVOKED 与 EXPIRED Token 被拒绝，不撤销 Family")
  void rotateTerminalStatusRejected() {
    RefreshSessionEntity revoked =
        RefreshSessionEntity.create(
            1L, 10L, 7L, "hmac", LocalDateTime.now(), LocalDateTime.now().plusDays(30));
    org.springframework.test.util.ReflectionTestUtils.setField(revoked, "status", "REVOKED");
    when(sessionDao.findByTokenHmac("hmac")).thenReturn(Optional.of(revoked));

    assertThrows(InvalidRefreshTokenException.class, () -> service.rotate("token"));
    verify(sessionDao, never()).revokeFamily(anyLong());
  }

  @Test
  @DisplayName("过期 ACTIVE Token 被拒绝")
  void rotateExpiredActiveRejected() {
    RefreshSessionEntity expired =
        RefreshSessionEntity.create(
            1L,
            10L,
            7L,
            "hmac",
            LocalDateTime.now().minusDays(40),
            LocalDateTime.now().minusDays(10));
    when(sessionDao.findByTokenHmac("hmac")).thenReturn(Optional.of(expired));

    assertThrows(InvalidRefreshTokenException.class, () -> service.rotate("token"));
  }

  @Test
  @DisplayName("用户被禁用时禁止刷新")
  void rotateDisabledUserRejected() {
    RefreshSessionEntity active =
        RefreshSessionEntity.create(
            1L, 10L, 7L, "hmac", LocalDateTime.now(), LocalDateTime.now().plusDays(30));
    when(sessionDao.findByTokenHmac("hmac")).thenReturn(Optional.of(active));
    PlatformUserEntity disabled = activeUser();
    disabled.setStatus(PlatformUserEntity.STATUS_DISABLED);
    when(userDao.findById(7L)).thenReturn(Optional.of(disabled));

    assertThrows(InvalidRefreshTokenException.class, () -> service.rotate("token"));
  }

  private static PlatformUserEntity activeUser() {
    return PlatformUserEntity.create(7L, "Alice");
  }

  private static LoginAccountEntity activeAccount() {
    return LoginAccountEntity.create(70L, 7L, "alice", "alice", "argon2-hash");
  }
}
