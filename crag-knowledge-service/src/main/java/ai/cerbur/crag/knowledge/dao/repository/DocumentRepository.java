package ai.cerbur.crag.knowledge.dao.repository;

import ai.cerbur.crag.knowledge.dao.entity.DocumentEntity;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Document Spring Data JPA Repository，仅允许 {@code ai.cerbur.crag.knowledge.dao} 包调用。
 *
 * <p>查询均携带 {@code tenantId}；{@link #updateIngestionStatus} 是 ingestion 状态推进 CAS 原语，WHERE 携带当前版本并
 * 在数据库侧递增 version，返回 affected rows。
 */
@Repository
public interface DocumentRepository extends JpaRepository<DocumentEntity, Long> {

  Optional<DocumentEntity> findByDocIdAndTenantId(long docId, long tenantId);

  Page<DocumentEntity> findByKnowledgeBaseIdAndTenantId(
      long knowledgeBaseId, long tenantId, Pageable pageable);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Transactional
  @Query(
      "UPDATE DocumentEntity d SET d.ingestionStatus = :newStatus, d.updatedAt = CURRENT_TIMESTAMP,"
          + " d.version = d.version + 1"
          + " WHERE d.docId = :docId AND d.tenantId = :tenantId AND d.version = :version")
  int updateIngestionStatus(
      @Param("docId") long docId,
      @Param("tenantId") long tenantId,
      @Param("newStatus") String newStatus,
      @Param("version") Long version);
}
