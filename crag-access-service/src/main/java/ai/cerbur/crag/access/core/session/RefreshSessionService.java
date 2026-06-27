package ai.cerbur.crag.access.core.session;

import ai.cerbur.crag.access.dao.LoginAccountDao;
import ai.cerbur.crag.access.dao.PlatformUserDao;
import ai.cerbur.crag.access.dao.RefreshSessionDao;
import ai.cerbur.crag.access.dao.entity.LoginAccountEntity;
import ai.cerbur.crag.access.dao.entity.PlatformUserEntity;
import ai.cerbur.crag.access.dao.entity.RefreshSessionEntity;
import ai.cerbur.crag.access.security.AccessSecurityConfiguration;
import ai.cerbur.crag.access.security.SecretGenerator;
import ai.cerbur.crag.access.security.SecretHmac;
import ai.cerbur.crag.id.api.CragIdGenerator;
import ai.cerbur.crag.id.api.IdEntityType;
import java.time.Duration;
import java.time.LocalDateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Refresh Session 用例：签发新 Family、单次轮换与复用检测。
 *
 * <p>Refresh Token 使用 32 字节随机秘密的 Base64URL 无填充编码，数据库按独立 Pepper 的 HMAC 唯一定位。轮换通过行锁 + 版本 CAS 保证单
 * Token 单次成功；ROTATED Token 再次出现视为复用攻击，原子撤销整个 Family。用户或账号被禁用时禁止刷新。
 */
@Service
public class RefreshSessionService {

  static final Duration REFRESH_TTL = Duration.ofDays(30);
  private static final int SECRET_BYTES = 32;

  @Autowired private RefreshSessionDao sessionDao;
  @Autowired private PlatformUserDao userDao;
  @Autowired private LoginAccountDao accountDao;
  @Autowired private CragIdGenerator idGenerator;
  @Autowired private SecretGenerator secretGenerator;

  @Autowired
  @Qualifier(AccessSecurityConfiguration.REFRESH_TOKEN_HMAC)
  private SecretHmac refreshHmac;

  /** 为用户签发新 Session Family 与首个 ACTIVE Token；返回完整 Token 材料。 */
  @Transactional
  public IssuedRefresh createNewFamily(long userId) {
    long familyId = idGenerator.nextId(IdEntityType.REFRESH_SESSION);
    return persist(familyId, userId);
  }

  /**
   * 轮换 Refresh Token：ACTIVE→ROTATED 并签发替代 Token；ROTATED 再次出现撤销整个 Family；过期/已撤销/禁用拒绝。
   *
   * @throws InvalidRefreshTokenException Token 无效、过期、已撤销或复用
   */
  @Transactional
  public IssuedRefresh rotate(String rawRefreshToken) {
    String hmac = refreshHmac.digest(rawRefreshToken);
    RefreshSessionEntity session =
        sessionDao.findByTokenHmacForUpdate(hmac).orElseThrow(InvalidRefreshTokenException::new);
    if (RefreshSessionEntity.STATUS_ROTATED.equals(session.getStatus())) {
      // 复用检测：旧 Token 再次出现，撤销整个 Family。
      sessionDao.revokeFamily(session.getFamilyId());
      throw new InvalidRefreshTokenException();
    }
    if (RefreshSessionEntity.STATUS_REVOKED.equals(session.getStatus())
        || RefreshSessionEntity.STATUS_EXPIRED.equals(session.getStatus())) {
      throw new InvalidRefreshTokenException();
    }
    if (session.getExpiresAt().isBefore(LocalDateTime.now())) {
      throw new InvalidRefreshTokenException();
    }
    requireUserActive(session.getUserId());
    long replacementSessionId = idGenerator.nextId(IdEntityType.REFRESH_SESSION);
    sessionDao.rotate(session.getSessionId(), session.getVersion(), replacementSessionId);
    return persist(session.getFamilyId(), session.getUserId());
  }

  /** 撤销整个 Family（Logout）。 */
  @Transactional
  public void revoke(long familyId) {
    sessionDao.revokeFamily(familyId);
  }

  /** 写入一条新 ACTIVE Session 并返回 Token 材料。 */
  private IssuedRefresh persist(long familyId, long userId) {
    long sessionId = idGenerator.nextId(IdEntityType.REFRESH_SESSION);
    String secret = secretGenerator.randomBase64Url(SECRET_BYTES);
    String hmac = refreshHmac.digest(secret);
    LocalDateTime now = LocalDateTime.now();
    LocalDateTime expiresAt = now.plus(REFRESH_TTL);
    sessionDao.insert(
        RefreshSessionEntity.create(sessionId, familyId, userId, hmac, now, expiresAt));
    return new IssuedRefresh(familyId, userId, secret, expiresAt);
  }

  private void requireUserActive(long userId) {
    PlatformUserEntity user =
        userDao.findById(userId).orElseThrow(InvalidRefreshTokenException::new);
    if (!PlatformUserEntity.STATUS_ACTIVE.equals(user.getStatus())) {
      throw new InvalidRefreshTokenException();
    }
    LoginAccountEntity account =
        accountDao.findByUserId(userId).orElseThrow(InvalidRefreshTokenException::new);
    if (!LoginAccountEntity.STATUS_ACTIVE.equals(account.getStatus())) {
      throw new InvalidRefreshTokenException();
    }
  }

  /** Refresh Token 签发/轮换结果。 */
  record IssuedRefresh(long familyId, long userId, String token, LocalDateTime expiresAt) {}
}
