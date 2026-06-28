package ai.cerbur.crag.knowledge.core.knowledgebase;

import ai.cerbur.crag.knowledge.dao.KnowledgeBaseDao;
import ai.cerbur.crag.knowledge.dao.entity.KnowledgeBaseEntity;
import ai.cerbur.crag.knowledge.producer.KnowledgeBaseCreatedOutboxWriter;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * KnowledgeBase 用例服务：创建、租户内查看与列表。查询均携带 {@code tenantId} 保证租户隔离；跨租户查询按 not found 处理。
 *
 * <p>plan_21/21.3：{@code create} 在同一事务写业务行与 {@code KNOWLEDGE_BASE_CREATED} Outbox 行；事务回滚时两者一并回滚。
 */
@Service
public class KnowledgeBaseService {

  @Autowired private KnowledgeBaseDao knowledgeBaseDao;
  @Autowired private KnowledgeBaseCreatedOutboxWriter knowledgeBaseCreatedOutboxWriter;

  /** 创建 ACTIVE 知识库，并在同一事务发布 {@code KNOWLEDGE_BASE_CREATED}。事务回滚时业务行与 Outbox 行同退。 */
  @Transactional
  public KnowledgeBaseResult create(long tenantId, String name, long createdByUserId) {
    KnowledgeBaseEntity kb =
        knowledgeBaseDao.insert(KnowledgeBaseEntity.create(tenantId, name, createdByUserId));
    KnowledgeBaseResult result = KnowledgeBaseResult.from(kb);
    knowledgeBaseCreatedOutboxWriter.write(result);
    return result;
  }

  /** 按知识库 ID 与租户查看；跨租户返回空。 */
  @Transactional(readOnly = true)
  public Optional<KnowledgeBaseResult> get(long knowledgeBaseId, long tenantId) {
    return knowledgeBaseDao
        .findByIdAndTenant(knowledgeBaseId, tenantId)
        .map(KnowledgeBaseResult::from);
  }

  /** 按租户分页列表。 */
  @Transactional(readOnly = true)
  public List<KnowledgeBaseResult> list(long tenantId, Pageable pageable) {
    return knowledgeBaseDao.listByTenant(tenantId, pageable).stream()
        .map(KnowledgeBaseResult::from)
        .toList();
  }
}
