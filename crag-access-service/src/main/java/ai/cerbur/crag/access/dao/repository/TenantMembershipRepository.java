package ai.cerbur.crag.access.dao.repository;

import ai.cerbur.crag.access.dao.entity.TenantMembershipEntity;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * tenant_membership Spring Data Repository，仅允许 {@code ai.cerbur.crag.access.dao} 包调用。
 *
 * <p>成员变更在事务内以悲观写锁读取 Tenant 的有效 OWNER 集合，再通过版本 CAS 推进目标成员状态；CAS 返回 affected rows， 由 DAO 判定抢占失败。
 */
@Repository
public interface TenantMembershipRepository extends JpaRepository<TenantMembershipEntity, Long> {

  Optional<TenantMembershipEntity> findByTenantIdAndUserId(long tenantId, long userId);

  List<TenantMembershipEntity> findByTenantIdOrderByUserIdAsc(long tenantId);

  Optional<TenantMembershipEntity> findFirstByUserIdAndStatusOrderByTenantIdAsc(
      long userId, String status);

  /** 悲观锁读取 Tenant 内指定状态的成员，用于锁定有效 OWNER 集合。 */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "SELECT m FROM TenantMembershipEntity m WHERE m.tenantId = :tenantId AND m.status = :status")
  List<TenantMembershipEntity> findByTenantIdAndStatusForUpdate(
      @Param("tenantId") long tenantId, @Param("status") String status);

  /** 成员角色/状态版本 CAS 更新；返回 affected rows。 */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      "UPDATE TenantMembershipEntity m SET m.role = :role, m.status = :status, "
          + "m.version = m.version + 1, m.updatedAt = :now "
          + "WHERE m.tenantId = :tenantId AND m.userId = :userId AND m.version = :expectedVersion")
  int updateRoleAndStatus(
      @Param("tenantId") long tenantId,
      @Param("userId") long userId,
      @Param("role") String role,
      @Param("status") String status,
      @Param("expectedVersion") long expectedVersion,
      @Param("now") LocalDateTime now);
}
