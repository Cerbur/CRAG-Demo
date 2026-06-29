package ai.cerbur.crag.access.core.membership;

import ai.cerbur.crag.access.dao.LoginAccountDao;
import ai.cerbur.crag.access.dao.PlatformUserDao;
import ai.cerbur.crag.access.dao.TenantDao;
import ai.cerbur.crag.access.dao.TenantMembershipDao;
import ai.cerbur.crag.access.dao.entity.LoginAccountEntity;
import ai.cerbur.crag.access.dao.entity.PlatformUserEntity;
import ai.cerbur.crag.access.dao.entity.TenantMembershipEntity;
import ai.cerbur.crag.id.api.CragIdGenerator;
import ai.cerbur.crag.id.api.IdEntityType;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Membership 用例：实时权限判断与 OWNER/MEMBER 生命周期管理。
 *
 * <p>管理命令先实时校验调用方为有效 OWNER，再在事务内悲观锁定 Tenant 与有效 OWNER 集合，保护最后一名 OWNER。被移除成员重新加入复用 原 REMOVED 行并设为
 * MEMBER。跨租户查询与不存在账号均使用不泄漏存在性的统一错误。
 */
@Service
public class MembershipService {

  @Autowired private TenantMembershipDao membershipDao;
  @Autowired private TenantDao tenantDao;
  @Autowired private LoginAccountDao accountDao;
  @Autowired private PlatformUserDao userDao;
  @Autowired private CragIdGenerator idGenerator;

  /** 实时权限判断。调用方无有效成员关系或权限不足均返回拒绝决策。 */
  @Transactional(readOnly = true)
  public AuthorizationDecision authorize(AuthorizationRequest request) {
    return membershipDao
        .findByTenantAndUser(request.tenantId(), request.actorUserId())
        .filter(m -> TenantMembershipEntity.STATUS_ACTIVE.equals(m.getStatus()))
        .map(
            m -> {
              boolean ownsResource =
                  request.resourceOwnerUserId() != null
                      && request.resourceOwnerUserId() == request.actorUserId();
              return TenantPermissionPolicy.decide(
                  MembershipRole.fromEntity(m.getRole()), request.action(), ownsResource);
            })
        .orElseGet(() -> AuthorizationDecision.deny(request.action()));
  }

  /** OWNER 按规范化 Username 添加已注册用户；REMOVED 行复用为 MEMBER。 */
  @Transactional
  public MembershipResult addByUsername(long actorUserId, long tenantId, String username) {
    requireOwner(actorUserId, tenantId);
    long targetUserId = resolveActiveUserIdByUsername(username);
    tenantDao.findForUpdate(tenantId);
    List<TenantMembershipEntity> active = activeMembershipsLocked(tenantId);

    return membershipDao
        .findByTenantAndUser(tenantId, targetUserId)
        .map(
            existing -> {
              if (TenantMembershipEntity.STATUS_ACTIVE.equals(existing.getStatus())) {
                throw new MembershipStateException("user is already an active member");
              }
              TenantMembershipEntity reactivated =
                  membershipDao.updateRoleAndStatus(
                      tenantId,
                      targetUserId,
                      TenantMembershipEntity.ROLE_MEMBER,
                      TenantMembershipEntity.STATUS_ACTIVE,
                      existing.getVersion());
              return MembershipResult.from(reactivated);
            })
        .orElseGet(
            () -> {
              TenantMembershipEntity inserted =
                  membershipDao.insert(
                      TenantMembershipEntity.create(
                          idGenerator.nextId(IdEntityType.TENANT_MEMBERSHIP),
                          tenantId,
                          targetUserId,
                          TenantMembershipEntity.ROLE_MEMBER,
                          TenantMembershipEntity.STATUS_ACTIVE));
              return MembershipResult.from(inserted);
            });
  }

  /** OWNER 调整成员角色；降级最后一名 OWNER 抛 {@link LastOwnerException}。 */
  @Transactional
  public MembershipResult changeRole(
      long actorUserId, long tenantId, long memberUserId, MembershipRole role) {
    requireOwner(actorUserId, tenantId);
    tenantDao.findForUpdate(tenantId);
    List<TenantMembershipEntity> active = activeMembershipsLocked(tenantId);
    TenantMembershipEntity target = requireActiveMember(tenantId, memberUserId);
    if (role == MembershipRole.MEMBER
        && TenantMembershipEntity.ROLE_OWNER.equals(target.getRole())
        && countActiveOwners(active) <= 1) {
      throw new LastOwnerException();
    }
    return MembershipResult.from(
        membershipDao.updateRoleAndStatus(
            tenantId,
            memberUserId,
            role.toEntity(),
            TenantMembershipEntity.STATUS_ACTIVE,
            target.getVersion()));
  }

  /** OWNER 移除成员；移除最后一名 OWNER 抛 {@link LastOwnerException}。 */
  @Transactional
  public MembershipResult remove(long actorUserId, long tenantId, long memberUserId) {
    requireOwner(actorUserId, tenantId);
    tenantDao.findForUpdate(tenantId);
    List<TenantMembershipEntity> active = activeMembershipsLocked(tenantId);
    TenantMembershipEntity target = requireActiveMember(tenantId, memberUserId);
    if (TenantMembershipEntity.ROLE_OWNER.equals(target.getRole())
        && countActiveOwners(active) <= 1) {
      throw new LastOwnerException();
    }
    return MembershipResult.from(
        membershipDao.updateRoleAndStatus(
            tenantId,
            memberUserId,
            target.getRole(),
            TenantMembershipEntity.STATUS_REMOVED,
            target.getVersion()));
  }

  /** 查询单条成员关系；调用方须为有效成员，跨租户不泄漏。 */
  @Transactional(readOnly = true)
  public MembershipResult get(long actorUserId, long tenantId, long memberUserId) {
    requireMember(actorUserId, tenantId);
    return MembershipResult.from(requireActiveMember(tenantId, memberUserId));
  }

  /** 列出 Tenant 有效成员；调用方须为有效成员。批量补齐 nickname（禁止逐行查 User）。 */
  @Transactional(readOnly = true)
  public List<MembershipResult> list(
      long actorUserId, long tenantId, int pageSize, String pageToken) {
    requireMember(actorUserId, tenantId);
    int limit = pageSize <= 0 ? 50 : Math.min(pageSize, 200);
    List<TenantMembershipEntity> memberships =
        membershipDao.listByTenant(tenantId).stream()
            .filter(m -> TenantMembershipEntity.STATUS_ACTIVE.equals(m.getStatus()))
            .limit(limit)
            .toList();
    if (memberships.isEmpty()) {
      return List.of();
    }
    Set<Long> userIds =
        memberships.stream().map(TenantMembershipEntity::getUserId).collect(Collectors.toSet());
    Map<Long, String> nicknames =
        userDao.findByIdIn(userIds).stream()
            .collect(
                Collectors.toMap(PlatformUserEntity::getUserId, PlatformUserEntity::getNickname));
    return memberships.stream()
        .map(m -> MembershipResult.from(m, nicknames.getOrDefault(m.getUserId(), "")))
        .toList();
  }

  private void requireOwner(long actorUserId, long tenantId) {
    TenantMembershipEntity actor =
        membershipDao
            .findByTenantAndUser(tenantId, actorUserId)
            .filter(m -> TenantMembershipEntity.STATUS_ACTIVE.equals(m.getStatus()))
            .orElseThrow(MembershipAuthorizationException::new);
    if (!TenantMembershipEntity.ROLE_OWNER.equals(actor.getRole())) {
      throw new MembershipAuthorizationException();
    }
  }

  private void requireMember(long actorUserId, long tenantId) {
    membershipDao
        .findByTenantAndUser(tenantId, actorUserId)
        .filter(m -> TenantMembershipEntity.STATUS_ACTIVE.equals(m.getStatus()))
        .orElseThrow(MembershipNotFoundException::new);
  }

  private TenantMembershipEntity requireActiveMember(long tenantId, long memberUserId) {
    return membershipDao
        .findByTenantAndUser(tenantId, memberUserId)
        .filter(m -> TenantMembershipEntity.STATUS_ACTIVE.equals(m.getStatus()))
        .orElseThrow(MembershipNotFoundException::new);
  }

  /** 悲观锁读取 Tenant 全部有效成员（用于锁定有效 OWNER 集合）。 */
  private List<TenantMembershipEntity> activeMembershipsLocked(long tenantId) {
    return membershipDao.findForUpdateByTenantAndStatus(
        tenantId, TenantMembershipEntity.STATUS_ACTIVE);
  }

  private static long countActiveOwners(List<TenantMembershipEntity> active) {
    return active.stream()
        .filter(m -> TenantMembershipEntity.ROLE_OWNER.equals(m.getRole()))
        .count();
  }

  /** 按规范化 Username 解析有效用户；不存在或禁用统一为 {@link MembershipNotFoundException}，不泄漏账号状态。 */
  private long resolveActiveUserIdByUsername(String username) {
    if (username == null) {
      throw new MembershipNotFoundException();
    }
    String normalized = username.trim().toLowerCase();
    LoginAccountEntity account =
        accountDao
            .findByNormalizedUsername(normalized)
            .orElseThrow(MembershipNotFoundException::new);
    if (!LoginAccountEntity.STATUS_ACTIVE.equals(account.getStatus())) {
      throw new MembershipNotFoundException();
    }
    PlatformUserEntity user =
        userDao.findById(account.getUserId()).orElseThrow(MembershipNotFoundException::new);
    if (!PlatformUserEntity.STATUS_ACTIVE.equals(user.getStatus())) {
      throw new MembershipNotFoundException();
    }
    return user.getUserId();
  }
}
