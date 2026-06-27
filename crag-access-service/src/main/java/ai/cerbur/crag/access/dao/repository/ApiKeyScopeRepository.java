package ai.cerbur.crag.access.dao.repository;

import ai.cerbur.crag.access.dao.entity.ApiKeyScopeEntity;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * api_key_scope Spring Data Repository，仅允许 {@code ai.cerbur.crag.access.dao} 包调用。
 *
 * <p>按 KnowledgeBase 查询 Scope；终态 Block 通过版本 CAS 推进，BLOCKED 为终态。
 */
@Repository
public interface ApiKeyScopeRepository extends JpaRepository<ApiKeyScopeEntity, Long> {

  Optional<ApiKeyScopeEntity> findByKnowledgeBaseId(long knowledgeBaseId);

  /** 终态阻塞版本 CAS：ACTIVE→BLOCKED；返回 affected rows。 */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      "UPDATE ApiKeyScopeEntity s SET s.status = :blocked, s.version = s.version + 1, "
          + "s.updatedAt = :now WHERE s.knowledgeBaseId = :knowledgeBaseId "
          + "AND s.status = :active AND s.version = :expectedVersion")
  int block(
      @Param("knowledgeBaseId") long knowledgeBaseId,
      @Param("expectedVersion") long expectedVersion,
      @Param("active") String activeStatus,
      @Param("blocked") String blockedStatus,
      @Param("now") LocalDateTime now);
}
