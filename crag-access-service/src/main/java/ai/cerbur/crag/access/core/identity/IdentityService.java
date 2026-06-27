package ai.cerbur.crag.access.core.identity;

import ai.cerbur.crag.access.core.membership.TenantRegistrationResult;
import ai.cerbur.crag.access.dao.LoginAccountDao;
import ai.cerbur.crag.access.dao.PlatformUserDao;
import ai.cerbur.crag.access.dao.TenantDao;
import ai.cerbur.crag.access.dao.TenantMembershipDao;
import ai.cerbur.crag.access.dao.entity.LoginAccountEntity;
import ai.cerbur.crag.access.dao.entity.PlatformUserEntity;
import ai.cerbur.crag.access.dao.entity.TenantEntity;
import ai.cerbur.crag.access.dao.entity.TenantMembershipEntity;
import ai.cerbur.crag.access.security.PasswordHasher;
import ai.cerbur.crag.id.api.CragIdGenerator;
import ai.cerbur.crag.id.api.IdEntityType;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Identity 用例：Username/密码注册与凭据认证。
 *
 * <p>注册在一个事务内原子创建 User、USERNAME Account、默认 Tenant 与 OWNER Membership，任一步失败整体回滚；Username 冲突抛 {@link
 * UsernameConflictException}。登录按规范化 Username 定位账号并校验 Argon2id，失败统一抛 {@link
 * InvalidCredentialsException}，不泄漏 Username 是否存在。本任务不签发 JWT/Refresh Token（由 session 切片负责）。
 */
@Service
public class IdentityService {

  /** 默认 Tenant 展示名前缀模板。 */
  static final String DEFAULT_TENANT_NAME_FORMAT = "%s 的空间";

  @Autowired private PlatformUserDao userDao;
  @Autowired private LoginAccountDao accountDao;
  @Autowired private TenantDao tenantDao;
  @Autowired private TenantMembershipDao membershipDao;
  @Autowired private PasswordHasher passwordHasher;
  @Autowired private CragIdGenerator idGenerator;

  /**
   * 注册 User、USERNAME Account、默认 Tenant 与 OWNER Membership。
   *
   * @throws IllegalArgumentException Nickname/Username/密码格式不合规
   * @throws UsernameConflictException Username 已被占用
   */
  @Transactional
  public RegisteredIdentity register(RegisterIdentityCommand command) {
    String nickname = IdentityPolicy.normalizeNickname(command.nickname());
    String username = IdentityPolicy.normalizeUsername(command.username());
    char[] password = command.password() == null ? new char[0] : command.password();
    try {
      IdentityPolicy.validatePassword(password);
      if (accountDao.findByNormalizedUsername(username).isPresent()) {
        throw new UsernameConflictException();
      }
      long userId = idGenerator.nextId(IdEntityType.USER);
      long accountId = idGenerator.nextId(IdEntityType.LOGIN_ACCOUNT);
      String credentialHash = passwordHasher.hash(password);
      userDao.insert(PlatformUserEntity.create(userId, nickname));
      accountDao.insert(
          LoginAccountEntity.create(accountId, userId, username, username, credentialHash));
      TenantRegistrationResult tenant = createDefaultTenant(userId, nickname);
      return new RegisteredIdentity(userId, accountId, tenant.tenantId(), tenant.membershipId());
    } finally {
      Arrays.fill(password, '\0');
    }
  }

  /**
   * 校验 Username/密码并返回身份摘要。
   *
   * @throws InvalidCredentialsException Username 不存在、密码错误、账号或用户禁用
   */
  @Transactional(readOnly = true)
  public AuthenticatedIdentity authenticate(String rawUsername, char[] password) {
    String username = lenientLookupKey(rawUsername);
    char[] pwd = password == null ? new char[0] : password;
    try {
      LoginAccountEntity account =
          accountDao
              .findByNormalizedUsername(username)
              .orElseThrow(InvalidCredentialsException::new);
      if (!LoginAccountEntity.STATUS_ACTIVE.equals(account.getStatus())) {
        throw new InvalidCredentialsException();
      }
      PlatformUserEntity user =
          userDao.findById(account.getUserId()).orElseThrow(InvalidCredentialsException::new);
      if (!PlatformUserEntity.STATUS_ACTIVE.equals(user.getStatus())) {
        throw new InvalidCredentialsException();
      }
      if (!passwordHasher.matches(pwd, account.getCredentialHash())) {
        throw new InvalidCredentialsException();
      }
      return new AuthenticatedIdentity(
          user.getUserId(), account.getAccountId(), user.getNickname());
    } finally {
      Arrays.fill(pwd, '\0');
    }
  }

  /** 创建默认 Tenant 与 OWNER Membership。 */
  private TenantRegistrationResult createDefaultTenant(long ownerUserId, String nickname) {
    long tenantId = idGenerator.nextId(IdEntityType.TENANT);
    long membershipId = idGenerator.nextId(IdEntityType.TENANT_MEMBERSHIP);
    tenantDao.insert(
        TenantEntity.create(tenantId, String.format(DEFAULT_TENANT_NAME_FORMAT, nickname)));
    membershipDao.insert(TenantMembershipEntity.createOwner(membershipId, tenantId, ownerUserId));
    return new TenantRegistrationResult(tenantId, membershipId);
  }

  /** 宽松归一化用于登录查询：不因格式错误抛异常，未命中按凭据无效处理，避免泄漏 Username 存在性。 */
  private static String lenientLookupKey(String rawUsername) {
    if (rawUsername == null) {
      return "";
    }
    return rawUsername.trim().toLowerCase();
  }
}
