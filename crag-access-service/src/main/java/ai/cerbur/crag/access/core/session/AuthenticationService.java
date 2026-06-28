package ai.cerbur.crag.access.core.session;

import ai.cerbur.crag.access.core.identity.AuthenticatedIdentity;
import ai.cerbur.crag.access.core.identity.IdentityService;
import ai.cerbur.crag.access.core.identity.RegisterIdentityCommand;
import ai.cerbur.crag.access.core.identity.RegisteredIdentity;
import ai.cerbur.crag.access.dao.PlatformUserDao;
import ai.cerbur.crag.access.dao.TenantMembershipDao;
import ai.cerbur.crag.access.dao.entity.PlatformUserEntity;
import ai.cerbur.crag.access.metrics.AccessMetrics;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 认证 Facade：编排 Identity 注册/登录、Refresh Session 与 Access JWT 签发。
 *
 * <p>注册外层事务同时包含 Identity 注册与首个 Session 创建。Refresh 轮换与复用检测由 {@link RefreshSessionService} 负责；Access
 * JWT 由 {@link JwtIssuer} 签发。完整 Refresh Token 只在结果对象中返回一次。
 */
@Service
public class AuthenticationService {

  @Autowired private IdentityService identityService;
  @Autowired private RefreshSessionService refreshService;
  @Autowired private JwtIssuer jwtIssuer;
  @Autowired private PlatformUserDao userDao;
  @Autowired private TenantMembershipDao membershipDao;
  @Autowired private AccessMetrics metrics;

  /** 注册身份并签发首个 Token；Tenant 为新创建的默认 Tenant。 */
  @Transactional
  public AuthenticationResult register(RegisterIdentityCommand command) {
    RegisteredIdentity registered = identityService.register(command);
    metrics.authentication("register", true);
    return buildResult(
        registered.userId(),
        nicknameOf(registered.userId()),
        registered.tenantId(),
        refreshService.createNewFamily(registered.userId()));
  }

  /** 登录并签发新 Session Family 的 Token；回填调用方首个有效 Tenant。 */
  @Transactional
  public AuthenticationResult login(String username, char[] password) {
    AuthenticatedIdentity identity = identityService.authenticate(username, password);
    metrics.authentication("login", true);
    return buildResult(
        identity.userId(),
        identity.nickname(),
        membershipDao.findTenantIdForUser(identity.userId()).orElse(0L),
        refreshService.createNewFamily(identity.userId()));
  }

  /** 轮换 Refresh Token 并签发新 Access JWT。 */
  @Transactional
  public AuthenticationResult refresh(String refreshToken) {
    RefreshSessionService.IssuedRefresh rotated = refreshService.rotate(refreshToken);
    metrics.authentication("refresh", true);
    return buildResult(
        rotated.userId(),
        nicknameOf(rotated.userId()),
        membershipDao.findTenantIdForUser(rotated.userId()).orElse(0L),
        rotated);
  }

  /** 撤销当前 Session Family。 */
  @Transactional
  public void logout(long userId, long sessionFamilyId) {
    refreshService.revoke(sessionFamilyId);
  }

  /**
   * 按完整 Refresh Token 撤销 Session Family（plan_21/21.2 router4 Logout）。
   *
   * <p>不需要 Access JWT 或 userId/sessionFamilyId 输入；Console 从 HttpOnly Cookie 读取 Refresh Token 后直接调用。
   */
  @Transactional
  public void logout(String rawRefreshToken) {
    refreshService.revokeByRawRefreshToken(rawRefreshToken);
  }

  private AuthenticationResult buildResult(
      long userId, String nickname, long tenantId, RefreshSessionService.IssuedRefresh refresh) {
    IssuedJwt jwt = jwtIssuer.issue(userId, refresh.familyId(), Instant.now());
    return new AuthenticationResult(
        userId,
        nickname,
        tenantId,
        new TokenPair(
            jwt.token(),
            jwt.expiresAt(),
            refresh.token(),
            refresh.expiresAt().toInstant(ZoneOffset.UTC),
            refresh.familyId()));
  }

  private String nicknameOf(long userId) {
    PlatformUserEntity user = userDao.findById(userId).orElseThrow();
    return user.getNickname();
  }
}
