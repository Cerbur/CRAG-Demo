package ai.cerbur.crag.knowledge.dao;

import ai.cerbur.crag.knowledge.dao.entity.KnowledgeBaseEntity;
import ai.cerbur.crag.knowledge.dao.repository.KnowledgeBaseRepository;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

/**
 * KnowledgeBase 数据库访问边界，只依赖 {@link KnowledgeBaseRepository}。
 *
 * <p>查询均携带 {@code tenantId} 以保证租户隔离。
 */
@Component
public class KnowledgeBaseDao {

  @Autowired private KnowledgeBaseRepository knowledgeBaseRepository;

  /** 插入知识库；ID 由数据库 identity 列生成并回填。 */
  public KnowledgeBaseEntity insert(KnowledgeBaseEntity entity) {
    return knowledgeBaseRepository.save(entity);
  }

  /** 按知识库 ID 与租户查询；跨租户返回空。 */
  public Optional<KnowledgeBaseEntity> findByIdAndTenant(long knowledgeBaseId, long tenantId) {
    return knowledgeBaseRepository.findByKnowledgeBaseIdAndTenantId(knowledgeBaseId, tenantId);
  }

  /** 按租户分页列表。 */
  public Page<KnowledgeBaseEntity> listByTenant(long tenantId, Pageable pageable) {
    return knowledgeBaseRepository.findByTenantId(tenantId, pageable);
  }
}
