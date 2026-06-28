package ai.cerbur.crag.access.dao.repository;

import ai.cerbur.crag.access.dao.entity.TenantEntity;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * tenant Spring Data Repository，仅允许 {@code ai.cerbur.crag.access.dao} 包调用。
 *
 * <p>成员变更时以悲观写锁读取 Tenant 行，与有效 OWNER 集合共同保护最后一名 OWNER。
 */
@Repository
public interface TenantRepository extends JpaRepository<TenantEntity, Long> {

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT t FROM TenantEntity t WHERE t.tenantId = :tenantId")
  Optional<TenantEntity> findForUpdate(@Param("tenantId") long tenantId);

  /** 按 tenant_id 集合批量加载名称（plan_21/21.2 用户 Tenant 列表）。 */
  @Query("SELECT t FROM TenantEntity t WHERE t.tenantId IN :tenantIds")
  List<TenantEntity> findAllByIdIn(@Param("tenantIds") Collection<Long> tenantIds);
}
