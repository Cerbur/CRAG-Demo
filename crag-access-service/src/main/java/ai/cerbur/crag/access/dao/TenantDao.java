package ai.cerbur.crag.access.dao;

import ai.cerbur.crag.access.dao.entity.TenantEntity;
import ai.cerbur.crag.access.dao.repository.TenantRepository;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** tenant 数据库访问边界，只依赖 {@link TenantRepository}。 */
@Component
public class TenantDao {

  @Autowired private TenantRepository tenantRepository;

  /** 插入 Tenant；ID 由 Service 层分配。 */
  public TenantEntity insert(TenantEntity entity) {
    return tenantRepository.save(entity);
  }

  public Optional<TenantEntity> findById(long tenantId) {
    return tenantRepository.findById(tenantId);
  }

  /** 悲观锁读取 Tenant，用于成员变更时与有效 OWNER 集合共同保护最后一名 OWNER。 */
  public Optional<TenantEntity> findForUpdate(long tenantId) {
    return tenantRepository.findForUpdate(tenantId);
  }
}
