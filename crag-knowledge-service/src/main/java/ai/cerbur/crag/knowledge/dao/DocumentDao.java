package ai.cerbur.crag.knowledge.dao;

import ai.cerbur.crag.knowledge.dao.entity.DocumentEntity;
import ai.cerbur.crag.knowledge.dao.repository.DocumentRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

/**
 * Document 数据库访问边界，只依赖 {@link DocumentRepository}。
 *
 * <p>查询均携带 {@code tenantId}。状态推进有两套 CAS 原语：
 *
 * <ul>
 *   <li>{@link #updateIngestionStatus}（既有）：只按 docId + tenantId + version 推进 status；
 *   <li>{@link #applyIngestionProjection}（plan_21/21.3）：严格 CAS，WHERE 同时匹配 docId、tenantId、
 *       knowledgeBaseId、operationVersion 与 version，并写入完整投影字段，0 rows 抛 {@link
 *       VersionConflictException}。
 * </ul>
 *
 * <p>DAO 只处理原始类型与 {@link DocumentEntity}，不依赖 {@code core.ingestion} 包；状态枚举转换由调用方 （{@code
 * IngestionApplyService}）完成，保持 DAO 单向依赖。
 */
@Component
public class DocumentDao {

  @Autowired private DocumentRepository documentRepository;

  /** 插入文档；ID 由数据库 identity 列生成并回填。 */
  public DocumentEntity insert(DocumentEntity entity) {
    return documentRepository.save(entity);
  }

  /** 按文档 ID 与租户查询；跨租户返回空。 */
  public Optional<DocumentEntity> findByDocIdAndTenant(long docId, long tenantId) {
    return documentRepository.findByDocIdAndTenantId(docId, tenantId);
  }

  /** 按知识库与租户分页列表。 */
  public Page<DocumentEntity> listByKnowledgeBaseAndTenant(
      long knowledgeBaseId, long tenantId, Pageable pageable) {
    return documentRepository.findByKnowledgeBaseIdAndTenantId(knowledgeBaseId, tenantId, pageable);
  }

  /**
   * CAS 推进 ingestion_status；带版本条件并在数据库侧递增 version。
   *
   * @return affected rows（始终 ≥ 1）
   * @throws DuplicateKeyException 当 {@code affected == 0}（版本冲突，另一实例已接管）
   */
  public int updateIngestionStatus(long docId, long tenantId, String newStatus, Long version) {
    int affected = documentRepository.updateIngestionStatus(docId, tenantId, newStatus, version);
    if (affected == 0) {
      throw new DuplicateKeyException(
          "CAS updateIngestionStatus failed: doc "
              + docId
              + " version "
              + version
              + " already stale");
    }
    return affected;
  }

  /**
   * 严格 CAS 摄取投影更新（plan_21/21.3）。
   *
   * <p>WHERE 同时匹配 docId、tenantId、knowledgeBaseId、operationVersion 与 version，原子递增 version 并写入完整投影。
   * 所有字段使用原始类型，状态枚举/投影对象由调用方转换，保持 DAO 不依赖 {@code core.ingestion}。
   *
   * @return affected rows（始终 ≥ 1）
   * @throws VersionConflictException 当 {@code affected == 0}（version / operationVersion / tenant /
   *     knowledgeBase / docId 任一不匹配，或状态已被另一实例推进）
   */
  public int applyIngestionProjection(
      long docId,
      long tenantId,
      long knowledgeBaseId,
      long operationVersion,
      long expectedVersion,
      String status,
      int attempt,
      Long jobId,
      String failureCategory,
      String failureMessage,
      LocalDateTime startedAt,
      LocalDateTime completedAt,
      LocalDateTime nextRetryAt) {
    int affected =
        documentRepository.applyIngestionProjection(
            docId,
            tenantId,
            knowledgeBaseId,
            operationVersion,
            expectedVersion,
            status,
            attempt,
            jobId,
            failureCategory,
            failureMessage,
            startedAt,
            completedAt,
            nextRetryAt);
    if (affected == 0) {
      throw new VersionConflictException(
          "applyIngestionProjection CAS failed: doc "
              + docId
              + " tenant "
              + tenantId
              + " kb "
              + knowledgeBaseId
              + " opVersion "
              + operationVersion
              + " version "
              + expectedVersion
              + " already advanced");
    }
    return affected;
  }

  /**
   * Retry CAS（plan_21/21.5）：原子递增 operationVersion，重置 status=PENDING、清空失败字段，写入新 attempt。
   *
   * <p>WHERE 同时匹配 docId、tenantId、knowledgeBaseId、当前 operationVersion 与 version，0 rows 抛 {@link
   * VersionConflictException}，确保并发 retry 只允许一个新版本成功。
   *
   * @param docId 文档 ID
   * @param tenantId 租户 ID
   * @param knowledgeBaseId 知识库 ID
   * @param currentOperationVersion 当前 operationVersion
   * @param version 当前读取的行级 version
   * @param newAttempt 新 attempt 序号
   * @param newOperationVersion 新 operationVersion
   * @return affected rows（始终 ≥ 1）
   * @throws VersionConflictException 当 CAS 失败
   */
  public int retryIngestion(
      long docId,
      long tenantId,
      long knowledgeBaseId,
      long currentOperationVersion,
      long version,
      int newAttempt,
      long newOperationVersion) {
    int affected =
        documentRepository.retryIngestion(
            docId,
            tenantId,
            knowledgeBaseId,
            currentOperationVersion,
            version,
            newAttempt,
            newOperationVersion);
    if (affected == 0) {
      throw new VersionConflictException(
          "retryIngestion CAS failed: doc "
              + docId
              + " tenant "
              + tenantId
              + " kb "
              + knowledgeBaseId
              + " opVersion "
              + currentOperationVersion
              + " version "
              + version
              + " already advanced");
    }
    return affected;
  }

  /**
   * 查询 Reconciler 滞留候选（plan_21/21.5）。
   *
   * <p>PENDING 滞留以 updatedAt 早于阈值判断；PROCESSING 滞留以 startedAt 早于阈值判断。
   *
   * @param pendingThreshold PENDING 滞留上界
   * @param processingThreshold PROCESSING 滞留上界
   * @param batchSize 单批上限
   * @return 滞留候选文档
   */
  public Page<DocumentEntity> findStaleIngestionCandidates(
      LocalDateTime pendingThreshold, LocalDateTime processingThreshold, int batchSize) {
    return documentRepository.findStaleIngestionCandidates(
        pendingThreshold, processingThreshold, PageRequest.of(0, batchSize));
  }
}
