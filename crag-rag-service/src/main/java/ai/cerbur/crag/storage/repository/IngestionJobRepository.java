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
   * <p>Plan 21.4 起额外校验 {@code document_ingestion_head} 仍指向本 operationVersion；head 已被更高版本接管时
   * affected == 0，由 DAO 抛出 {@link IngestionJobConflictException}，迟到 Worker 无法 READY 一个已被取代的版本.
   *
   * @param docId 文档 ID
   * @param operationVersion 文档操作版本
   * @param version 当前读取版本
   * @param completedAt 进入 READY 的时间
   * @return affected rows（1 = 成功，0 = 已非 PROCESSING / 版本已变 / head 已被取代）
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Transactional
  @Query(
      "UPDATE IngestionJob j SET j.status = ai.cerbur.crag.storage.entity.IngestionJobStatus.READY,"
          + " j.completedAt = :completedAt, j.updatedAt = CURRENT_TIMESTAMP, j.version = j.version + 1"
          + " WHERE j.docId = :docId AND j.operationVersion = :operationVersion"
          + " AND j.status = ai.cerbur.crag.storage.entity.IngestionJobStatus.PROCESSING"
          + " AND j.version = :version"
          + " AND EXISTS (SELECT 1 FROM DocumentIngestionHead h"
          + " WHERE h.docId = j.docId AND h.operationVersion = j.operationVersion)")
  int tryMarkReady(
      @Param("docId") long docId,
      @Param("operationVersion") long operationVersion,
      @Param("version") Integer version,
      @Param("completedAt") LocalDateTime completedAt);

  /**
   * CAS 推进旧活动 Job（PENDING 或 PROCESSING）为 SUPERSEDED（Plan 21.4）.
   *
   * <p>仅当该 Job 的 operationVersion 严格小于 {@code newOperationVersion} 时生效，用于 head advance 把同 doc 的旧活动
   * Job 标记为已被取代。READY / FAILED 终态 Job 不被覆盖；affected == 0 表示 Job 已是终态或版本更高或版本已变.
   *
   * @param docId 文档 ID
   * @param newOperationVersion head 已推进到的更高 operationVersion
   * @return affected rows（1 = 旧活动 Job 被标记 SUPERSEDED，0 = 无可取代的旧活动 Job）
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Transactional
  @Query(
      "UPDATE IngestionJob j SET j.status = ai.cerbur.crag.storage.entity.IngestionJobStatus.SUPERSEDED,"
          + " j.updatedAt = CURRENT_TIMESTAMP, j.version = j.version + 1"
          + " WHERE j.docId = :docId AND j.operationVersion < :newOperationVersion"
          + " AND j.status IN (ai.cerbur.crag.storage.entity.IngestionJobStatus.PENDING,"
          + " ai.cerbur.crag.storage.entity.IngestionJobStatus.PROCESSING)")
  int tryMarkSuperseded(
      @Param("docId") long docId, @Param("newOperationVersion") long newOperationVersion);

  /**
   * CAS 推进滞留 PROCESSING Job 为 FAILED（Plan 21.4 timeout 终态化支撑）.
   *
   * <p>仅当 Job 为 PROCESSING、version 匹配且 {@code startedAt} 早于 {@code staleBefore} 时生效，用于 Reconciler
   * 终态化滞留任务（21.5 消费）。affected == 0 表示 Job 已推进、未超时或版本已变.
   *
   * @param docId 文档 ID
   * @param operationVersion 文档操作版本
   * @param version 当前读取版本
   * @param staleBefore 滞留时间上界，{@code started_at} 必须早于此值
   * @param completedAt 进入 FAILED 的时间
   * @param failureCategory 失败分类（安全枚举名）
   * @param failureMessage 失败安全短摘要
   * @return affected rows（1 = 成功，0 = 状态/版本/超时条件不满足）
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Transactional
  @Query(
      "UPDATE IngestionJob j SET j.status = ai.cerbur.crag.storage.entity.IngestionJobStatus.FAILED,"
          + " j.completedAt = :completedAt, j.failureCategory = :failureCategory,"
          + " j.failureMessage = :failureMessage, j.updatedAt = CURRENT_TIMESTAMP,"
          + " j.version = j.version + 1"
          + " WHERE j.docId = :docId AND j.operationVersion = :operationVersion"
          + " AND j.status = ai.cerbur.crag.storage.entity.IngestionJobStatus.PROCESSING"
          + " AND j.version = :version AND j.startedAt < :staleBefore")
  int tryMarkTimedOut(
      @Param("docId") long docId,
      @Param("operationVersion") long operationVersion,
      @Param("version") Integer version,
      @Param("staleBefore") LocalDateTime staleBefore,
      @Param("completedAt") LocalDateTime completedAt,
      @Param("failureCategory") String failureCategory,
      @Param("failureMessage") String failureMessage);

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
