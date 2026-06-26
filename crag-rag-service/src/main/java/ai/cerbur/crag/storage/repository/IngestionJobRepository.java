package ai.cerbur.crag.storage.repository;

import ai.cerbur.crag.storage.entity.IngestionJob;
import ai.cerbur.crag.storage.entity.IngestionJobStatus;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * IngestionJob Repository —— ingestion_job 表数据访问（Plan 19）.
 *
 * <p>提供按业务幂等键 {@code (docId, operationVersion)} 查询，以及 PENDING → PROCESSING → READY / FAILED 的 CAS
 * 状态推进。CAS 更新返回 affected rows（0 = 状态或版本已变），业务判断在 DAO 层完成.
 *
 * @since 2026-06-27
 */
@Repository
public interface IngestionJobRepository extends JpaRepository<IngestionJob, Long> {

  /** 按业务幂等键 {@code (docId, operationVersion)} 查询 Job，用于消费幂等判定. */
  Optional<IngestionJob> findByDocIdAndOperationVersion(long docId, long operationVersion);

  /** 按 KB + docId 查询 Job，供 smoke 诊断与查询隔离观察. */
  Optional<IngestionJob> findByKnowledgeBaseIdAndDocId(long knowledgeBaseId, long docId);

  /** 按文档 ID + 状态查询 Job，供索引完成推进 READY 使用. */
  Optional<IngestionJob> findByDocIdAndStatus(long docId, IngestionJobStatus status);

  /**
   * CAS 状态推进 PENDING → PROCESSING，并写入 started_at.
   *
   * @param docId 文档 ID
   * @param operationVersion 文档操作版本
   * @param version 当前读取版本
   * @param startedAt 进入 PROCESSING 的时间
   * @return affected rows（1 = 抢占成功，0 = 已非 PENDING 或版本已变）
   */
  @Modifying
  @Transactional
  @Query(
      "UPDATE IngestionJob j SET j.status = ai.cerbur.crag.storage.entity.IngestionJobStatus.PROCESSING,"
          + " j.startedAt = :startedAt, j.updatedAt = CURRENT_TIMESTAMP, j.version = j.version + 1"
          + " WHERE j.docId = :docId AND j.operationVersion = :operationVersion"
          + " AND j.status = ai.cerbur.crag.storage.entity.IngestionJobStatus.PENDING"
          + " AND j.version = :version")
  int tryMarkProcessing(
      @Param("docId") long docId,
      @Param("operationVersion") long operationVersion,
      @Param("version") Integer version,
      @Param("startedAt") LocalDateTime startedAt);

  /**
   * CAS 状态推进 PROCESSING → READY，并写入 completed_at.
   *
   * @param docId 文档 ID
   * @param operationVersion 文档操作版本
   * @param version 当前读取版本
   * @param completedAt 进入 READY 的时间
   * @return affected rows（1 = 成功，0 = 已非 PROCESSING 或版本已变）
   */
  @Modifying
  @Transactional
  @Query(
      "UPDATE IngestionJob j SET j.status = ai.cerbur.crag.storage.entity.IngestionJobStatus.READY,"
          + " j.completedAt = :completedAt, j.updatedAt = CURRENT_TIMESTAMP, j.version = j.version + 1"
          + " WHERE j.docId = :docId AND j.operationVersion = :operationVersion"
          + " AND j.status = ai.cerbur.crag.storage.entity.IngestionJobStatus.PROCESSING"
          + " AND j.version = :version")
  int tryMarkReady(
      @Param("docId") long docId,
      @Param("operationVersion") long operationVersion,
      @Param("version") Integer version,
      @Param("completedAt") LocalDateTime completedAt);

  /**
   * CAS 状态推进 PROCESSING → FAILED，写入 completed_at、失败分类与安全短摘要.
   *
   * @param docId 文档 ID
   * @param operationVersion 文档操作版本
   * @param version 当前读取版本
   * @param completedAt 进入 FAILED 的时间
   * @param failureCategory 失败分类（安全枚举名）
   * @param failureMessage 失败安全短摘要
   * @return affected rows（1 = 成功，0 = 已非 PROCESSING 或版本已变）
   */
  @Modifying
  @Transactional
  @Query(
      "UPDATE IngestionJob j SET j.status = ai.cerbur.crag.storage.entity.IngestionJobStatus.FAILED,"
          + " j.completedAt = :completedAt, j.failureCategory = :failureCategory,"
          + " j.failureMessage = :failureMessage, j.updatedAt = CURRENT_TIMESTAMP,"
          + " j.version = j.version + 1"
          + " WHERE j.docId = :docId AND j.operationVersion = :operationVersion"
          + " AND j.status = ai.cerbur.crag.storage.entity.IngestionJobStatus.PROCESSING"
          + " AND j.version = :version")
  int tryMarkFailed(
      @Param("docId") long docId,
      @Param("operationVersion") long operationVersion,
      @Param("version") Integer version,
      @Param("completedAt") LocalDateTime completedAt,
      @Param("failureCategory") String failureCategory,
      @Param("failureMessage") String failureMessage);

  /** 统计指定 KB 下处于给定状态的 Job 数量，供 smoke 诊断观察. */
  long countByKnowledgeBaseIdAndStatus(long knowledgeBaseId, IngestionJobStatus status);
}
