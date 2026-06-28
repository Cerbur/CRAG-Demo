package ai.cerbur.crag.storage.repository;

import ai.cerbur.crag.storage.entity.DocumentIngestionHead;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * DocumentIngestionHead Repository —— document_ingestion_head 表数据访问（Plan 21.4）.
 *
 * <p>提供按 {@code docId} 查询 head，以及单调推进 operationVersion 的 CAS 更新。CAS 更新只允许更高 operationVersion 写入，并在
 * SET 递增 version；affected == 0 表示已有等于或更高的版本在并发中胜出， 由 DAO/Service 层按业务语义处理（幂等 ACK 或拒绝降级）.
 *
 * @since 2026-06-28
 */
@Repository
public interface DocumentIngestionHeadRepository
    extends JpaRepository<DocumentIngestionHead, Long> {

  /** 按 docId 查询 head 行. */
  Optional<DocumentIngestionHead> findByDocId(long docId);

  /**
   * CAS 推进 head operationVersion（Plan 21.4）.
   *
   * <p>仅当 head 存在且 {@code :newOperationVersion > head.operation_version} 且 {@code version}
   * 匹配时更新；SET 同时 写入新 operationVersion 并递增 version。affected == 0 表示：head 不存在（调用方应先插入）、新版本不高于当前
   * （幂等场景，调用方应视为 ACK），或 version 已变（并发抢占失败）.
   *
   * @param docId 文档 ID
   * @param newOperationVersion 新的 operationVersion，必须严格大于当前
   * @param version 当前读取的版本号
   * @param updatedAt 更新时间
   * @return affected rows（1 = 推进成功，0 = 版本不更高 / head 不存在 / version 已变）
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Transactional
  @Query(
      "UPDATE DocumentIngestionHead h SET h.operationVersion = :newOperationVersion,"
          + " h.updatedAt = :updatedAt, h.version = h.version + 1"
          + " WHERE h.docId = :docId AND h.operationVersion < :newOperationVersion"
          + " AND h.version = :version")
  int tryAdvance(
      @Param("docId") long docId,
      @Param("newOperationVersion") long newOperationVersion,
      @Param("version") Integer version,
      @Param("updatedAt") java.time.LocalDateTime updatedAt);
}
