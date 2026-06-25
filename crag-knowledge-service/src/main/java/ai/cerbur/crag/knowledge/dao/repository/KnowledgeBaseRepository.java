package ai.cerbur.crag.knowledge.dao.repository;

import ai.cerbur.crag.knowledge.dao.entity.KnowledgeBaseEntity;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * KnowledgeBase Spring Data JPA Repository，仅允许 {@code ai.cerbur.crag.knowledge.dao} 包调用。
 *
 * <p>查询均携带 {@code tenantId}，保证租户隔离。
 */
@Repository
public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBaseEntity, Long> {

  Optional<KnowledgeBaseEntity> findByKnowledgeBaseIdAndTenantId(
      long knowledgeBaseId, long tenantId);

  Page<KnowledgeBaseEntity> findByTenantId(long tenantId, Pageable pageable);
}
