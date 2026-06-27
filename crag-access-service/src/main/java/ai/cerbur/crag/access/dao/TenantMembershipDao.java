package ai.cerbur.crag.access.dao;

import ai.cerbur.crag.access.dao.entity.TenantMembershipEntity;
import ai.cerbur.crag.access.dao.repository.TenantMembershipRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * tenant_membership 数据库访问边界，只依赖 {@link TenantMembershipRepository}。
 *
 * <p>成员变更事务内以悲观锁读取 Tenant 有效 OWNER 集合，再通过版本 CAS 推进目标成员；affected rows 为零时抛 {@link
 * VersionConflictException}，调用方按业务语义处理。
 */
@Component
public class TenantMembershipDao {

  @Autowired private TenantMembershipRepository tenantMembershipRepository;

  /** 插入成员关系；ID 由 Service 层分配。 */
  public TenantMembershipEntity insert(TenantMembershipEntity entity) {
    return tenantMembershipRepository.save(entity);
  }

  public Optional<TenantMembershipEntity> findByTenantAndUser(long tenantId, long userId) {
    return tenantMembershipRepository.findByTenantIdAndUserId(tenantId, userId);
  }

  /** 悲观锁读取 Tenant 内指定状态的成员（用于锁定有效 OWNER 集合）。 */
  public List<TenantMembershipEntity> findForUpdateByTenantAndStatus(long tenantId, String status) {
    return tenantMembershipRepository.findByTenantIdAndStatusForUpdate(tenantId, status);
  }

  /**
   * 成员角色/状态版本 CAS 更新。
   *
   * @return 更新后重新读取的成员关系
   * @throws VersionConflictException 版本不匹配（affected rows 为零）
   */
  public TenantMembershipEntity updateRoleAndStatus(
      long tenantId, long userId, String role, String status, long expectedVersion) {
    int affected =
        tenantMembershipRepository.updateRoleAndStatus(
            tenantId, userId, role, status, expectedVersion, LocalDateTime.now());
    if (affected == 0) {
      throw new VersionConflictException(
          "membership CAS failed: tenant="
              + tenantId
              + " user="
              + userId
              + " version="
              + expectedVersion);
    }
    return findByTenantAndUser(tenantId, userId)
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "membership vanished after CAS: tenant=" + tenantId + " user=" + userId));
  }

  public List<TenantMembershipEntity> listByTenant(long tenantId) {
    return tenantMembershipRepository.findByTenantIdOrderByUserIdAsc(tenantId);
  }

  /** 返回用户首个有效成员关系所在 Tenant（用于登录/刷新回填 Tenant 上下文）。 */
  public Optional<Long> findTenantIdForUser(long userId) {
    return tenantMembershipRepository
        .findFirstByUserIdAndStatusOrderByTenantIdAsc(userId, TenantMembershipEntity.STATUS_ACTIVE)
        .map(TenantMembershipEntity::getTenantId);
  }
}
