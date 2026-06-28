package ai.cerbur.crag.access.core.identity;

import ai.cerbur.crag.access.core.membership.MembershipRole;
import ai.cerbur.crag.access.core.membership.TenantRegistrationResult;
import ai.cerbur.crag.access.core.membership.UserTenantResult;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
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

  /**
   * 返回当前用户安全投影（plan_21/21.2）。
   *
   * @throws IllegalArgumentException 用户不存在
   */
  @Transactional(readOnly = true)
  public UserProfileResult getProfile(long userId) {
    PlatformUserEntity user =
        userDao.findById(userId).orElseThrow(() -> new IllegalArgumentException("user not found"));
    return new UserProfileResult(user.getUserId(), user.getNickname());
  }

  /** 列出当前用户有效 Tenant 与角色（plan_21/21.2）。批量加载 Tenant 名称，不逐行查询；分页游标为 tenantId。 */
  @Transactional(readOnly = true)
  public List<UserTenantResult> listUserTenants(long userId, int pageSize, String pageToken) {
    return listUserTenantsPage(userId, pageSize, pageToken).items();
  }

  /** 用户 Tenant 列表分页结果，包含 nextToken（plan_21/21.2）。 */
  public record UserTenantsPage(List<UserTenantResult> items, String nextPageToken) {
    public UserTenantsPage {
      items = items == null ? List.of() : List.copyOf(items);
    }
  }

  /** 用户 Tenant 列表带 nextToken 版本，供 gRPC Provider 直接映射分页响应。 */
  @Transactional(readOnly = true)
  public UserTenantsPage listUserTenantsPage(long userId, int pageSize, String pageToken) {
    int limit = pageSize <= 0 ? 20 : Math.min(pageSize, 100);
    long after = parseTenantPageToken(pageToken);
    List<TenantMembershipEntity> memberships = membershipDao.listActiveByUser(userId);
    List<TenantMembershipEntity> page =
        memberships.stream().filter(m -> m.getTenantId() > after).limit(limit + 1).toList();
    boolean hasMore = page.size() > limit;
    List<TenantMembershipEntity> current = page.stream().limit(limit).toList();
    if (current.isEmpty()) {
      return new UserTenantsPage(List.of(), null);
    }
    Set<Long> tenantIds =
        current.stream().map(TenantMembershipEntity::getTenantId).collect(Collectors.toSet());
    Map<Long, String> tenantNames =
        tenantDao.findAllByIdIn(tenantIds).stream()
            .collect(Collectors.toMap(TenantEntity::getTenantId, TenantEntity::getName));
    List<UserTenantResult> results =
        current.stream()
            .map(
                m ->
                    new UserTenantResult(
                        m.getTenantId(),
                        tenantNames.getOrDefault(m.getTenantId(), ""),
                        MembershipRole.fromEntity(m.getRole())))
            .toList();
    String nextToken =
        hasMore ? Long.toString(current.get(current.size() - 1).getTenantId()) : null;
    return new UserTenantsPage(results, nextToken);
  }

  /** 解析 Tenant 分页游标；非法值统一从头开始。 */
  private static long parseTenantPageToken(String pageToken) {
    if (pageToken == null || pageToken.isBlank()) {
      return 0L;
    }
    try {
      long parsed = Long.parseLong(pageToken.trim());
      return parsed < 0 ? 0L : parsed;
    } catch (NumberFormatException e) {
      return 0L;
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
