package ai.cerbur.crag.knowledge.dao.repository;

import ai.cerbur.crag.knowledge.dao.entity.DocumentEntity;
import java.time.LocalDateTime;
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
 * 在数据库侧递增 version，返回 affected rows。{@link #applyIngestionProjection}（plan_21/21.3）是更严格的 CAS：WHERE
 * 同时匹配 docId、tenantId、knowledgeBaseId、operationVersion 与 version，并写入完整投影字段。
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

  /**
   * 严格 CAS 摄取投影更新（plan_21/21.3）：WHERE 同时匹配 docId、tenantId、knowledgeBaseId、operationVersion 与
   * version，原子递增 version 并写入 status / attempt / jobId / 失败字段 / 时间字段。返回 affected rows（始终 ≥ 1）。
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Transactional
  @Query(
      "UPDATE DocumentEntity d SET "
          + "d.ingestionStatus = :status, "
          + "d.ingestionAttempt = :attempt, "
          + "d.ingestionJobId = :jobId, "
          + "d.failureCategory = :failureCategory, "
          + "d.failureMessage = :failureMessage, "
          + "d.startedAt = :startedAt, "
          + "d.completedAt = :completedAt, "
          + "d.nextRetryAt = :nextRetryAt, "
          + "d.updatedAt = CURRENT_TIMESTAMP, "
          + "d.version = d.version + 1 "
          + "WHERE d.docId = :docId "
          + "AND d.tenantId = :tenantId "
          + "AND d.knowledgeBaseId = :knowledgeBaseId "
          + "AND d.operationVersion = :operationVersion "
          + "AND d.version = :version")
  int applyIngestionProjection(
      @Param("docId") long docId,
      @Param("tenantId") long tenantId,
      @Param("knowledgeBaseId") long knowledgeBaseId,
      @Param("operationVersion") long operationVersion,
      @Param("version") Long version,
      @Param("status") String status,
      @Param("attempt") int attempt,
      @Param("jobId") Long jobId,
      @Param("failureCategory") String failureCategory,
      @Param("failureMessage") String failureMessage,
      @Param("startedAt") LocalDateTime startedAt,
      @Param("completedAt") LocalDateTime completedAt,
      @Param("nextRetryAt") LocalDateTime nextRetryAt);

  /**
   * Retry CAS（plan_21/21.5）：原子递增 operationVersion 与 version，重置
   * status=PENDING、清空失败字段/时间字段/nextRetryAt， 写入新 attempt。WHERE 同时匹配
   * docId、tenantId、knowledgeBaseId、当前 operationVersion 与 version，确保并发只允许一个新版本成功。
   *
   * @param docId 文档 ID
   * @param tenantId 租户 ID
   * @param knowledgeBaseId 知识库 ID
   * @param currentOperationVersion 当前 operationVersion（重试前）
   * @param version 当前读取的行级 version
   * @param newAttempt 新 attempt 序号（递增后）
   * @param newOperationVersion 新 operationVersion（递增后）
   * @return affected rows（1 = retry 成功，0 = CAS 冲突）
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Transactional
  @Query(
      "UPDATE DocumentEntity d SET "
          + "d.ingestionStatus = 'PENDING', "
          + "d.operationVersion = :newOperationVersion, "
          + "d.ingestionAttempt = :newAttempt, "
          + "d.ingestionJobId = NULL, "
          + "d.failureCategory = NULL, "
          + "d.failureMessage = NULL, "
          + "d.startedAt = NULL, "
          + "d.completedAt = NULL, "
          + "d.nextRetryAt = NULL, "
          + "d.updatedAt = CURRENT_TIMESTAMP, "
          + "d.version = d.version + 1 "
          + "WHERE d.docId = :docId "
          + "AND d.tenantId = :tenantId "
          + "AND d.knowledgeBaseId = :knowledgeBaseId "
          + "AND d.operationVersion = :currentOperationVersion "
          + "AND d.version = :version")
  int retryIngestion(
      @Param("docId") long docId,
      @Param("tenantId") long tenantId,
      @Param("knowledgeBaseId") long knowledgeBaseId,
      @Param("currentOperationVersion") long currentOperationVersion,
      @Param("version") Long version,
      @Param("newAttempt") int newAttempt,
      @Param("newOperationVersion") long newOperationVersion);

  /**
   * 查询 Reconciler 滞留候选（plan_21/21.5）：PENDING 超过 pendingThreshold 或 PROCESSING 超过
   * processingThreshold 的文档。
   *
   * <p>PENDING 滞留以 {@code updatedAt} 早于阈值判断（上传后无任何事件推进）；PROCESSING 滞留以 {@code startedAt}
   * 早于阈值判断。分页限制单批规模。
   *
   * @param pendingThreshold PENDING 滞留上界（updatedAt 早于此值）
   * @param processingThreshold PROCESSING 滞留上界（startedAt 早于此值）
   * @param pageable 分页限制
   * @return 滞留候选文档
   */
  @org.springframework.data.jpa.repository.Query(
      "SELECT d FROM DocumentEntity d WHERE d.ingestionStatus = 'PENDING' AND d.updatedAt < :pendingThreshold"
          + " OR (d.ingestionStatus = 'PROCESSING' AND d.startedAt < :processingThreshold)")
  org.springframework.data.domain.Page<DocumentEntity> findStaleIngestionCandidates(
      @Param("pendingThreshold") LocalDateTime pendingThreshold,
      @Param("processingThreshold") LocalDateTime processingThreshold,
      org.springframework.data.domain.Pageable pageable);
}
