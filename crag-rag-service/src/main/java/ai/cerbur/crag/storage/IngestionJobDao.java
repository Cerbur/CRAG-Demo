package ai.cerbur.crag.storage;

import ai.cerbur.crag.storage.entity.IngestionJob;
import ai.cerbur.crag.storage.entity.IngestionJobStatus;
import ai.cerbur.crag.storage.repository.IngestionJobRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * IngestionJob DAO —— ingestion_job 表业务数据访问，只依赖 IngestionJobRepository（Plan 19）.
 *
 * <p>职责：
 *
 * <ul>
 *   <li>按业务幂等键 {@code (docId, operationVersion)} 幂等创建或返回已有 Job；
 *   <li>CAS 状态推进 PENDING → PROCESSING → READY / FAILED，affected == 0 时抛出 {@link
 *       IngestionJobConflictException}；
 *   <li>供 smoke 诊断的按 KB / 业务键查询。
 * </ul>
 *
 * @since 2026-06-27
 */
@Component
public class IngestionJobDao {

  private static final Logger log = LoggerFactory.getLogger(IngestionJobDao.class);

  @Autowired private IngestionJobRepository ingestionJobRepository;

  /**
   * 幂等创建或返回已有 Job.
   *
   * <p>以 {@code (docId, operationVersion)} 唯一约束保证：首次创建返回 PENDING 新 Job；重复业务键命中唯一约束，回退查询返回 已有
   * Job（可能为 PENDING / PROCESSING / READY / FAILED）。调用方据此决定是否继续处理.
   *
   * @param tenantId 租户 ID
   * @param knowledgeBaseId 知识库 ID
   * @param docId 文档 ID
   * @param operationVersion 文档操作版本
   * @param fileType 文件类型展示值
   * @param sizeBytes 文件字节数
   * @param sha256 文件 sha256
   * @return 已存在的或新建的 IngestionJob（持久化）
   */
  public IngestionJob findOrCreate(
      long tenantId,
      long knowledgeBaseId,
      long docId,
      long operationVersion,
      String fileType,
      long sizeBytes,
      String sha256) {
    Optional<IngestionJob> existing =
        ingestionJobRepository.findByDocIdAndOperationVersion(docId, operationVersion);
    if (existing.isPresent()) {
      return existing.get();
    }
    IngestionJob job =
        IngestionJob.createPending(
            tenantId, knowledgeBaseId, docId, operationVersion, fileType, sizeBytes, sha256);
    try {
      return ingestionJobRepository.save(job);
    } catch (DataIntegrityViolationException e) {
      // 并发：另一实例已按同一业务键创建。回退查询保证返回唯一 Job。
      IngestionJob concurrent =
          ingestionJobRepository
              .findByDocIdAndOperationVersion(docId, operationVersion)
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "ingestion_job unique constraint fired but row not found for docId="
                              + docId
                              + " operationVersion="
                              + operationVersion,
                          e));
      log.warn(
          "Concurrent ingestion_job create resolved to existing — docId={} operationVersion={}",
          docId,
          operationVersion);
      return concurrent;
    }
  }

  /** 按业务幂等键查询 Job. */
  public Optional<IngestionJob> findByDocIdAndOperationVersion(long docId, long operationVersion) {
    return ingestionJobRepository.findByDocIdAndOperationVersion(docId, operationVersion);
  }

  /** 按 KB + docId 查询 Job，供 smoke 诊断. */
  public Optional<IngestionJob> findByKnowledgeBaseIdAndDocId(long knowledgeBaseId, long docId) {
    return ingestionJobRepository.findByKnowledgeBaseIdAndDocId(knowledgeBaseId, docId);
  }

  /**
   * CAS 状态推进 PENDING → PROCESSING.
   *
   * @param job 当前读取的 Job（携带版本）
   * @param startedAt 进入 PROCESSING 的时间
   * @throws IngestionJobConflictException affected == 0（状态已非 PENDING 或版本已变）
   */
  public void markProcessing(IngestionJob job, LocalDateTime startedAt) {
    int affected =
        ingestionJobRepository.tryMarkProcessing(
            job.getDocId(), job.getOperationVersion(), job.getVersion(), startedAt);
    if (affected == 0) {
      throw new IngestionJobConflictException(
          job.getDocId(),
          job.getOperationVersion(),
          "markProcessing CAS failed: job already advanced");
    }
    job.setStatus(IngestionJobStatus.PROCESSING);
    job.setStartedAt(startedAt);
    job.setVersion(job.getVersion() + 1);
  }

  /**
   * CAS 状态推进 PROCESSING → READY.
   *
   * @param job 当前读取的 Job（携带版本）
   * @param completedAt 进入 READY 的时间
   * @throws IngestionJobConflictException affected == 0
   */
  public void markReady(IngestionJob job, LocalDateTime completedAt) {
    int affected =
        ingestionJobRepository.tryMarkReady(
            job.getDocId(), job.getOperationVersion(), job.getVersion(), completedAt);
    if (affected == 0) {
      throw new IngestionJobConflictException(
          job.getDocId(), job.getOperationVersion(), "markReady CAS failed: job already advanced");
    }
    job.setStatus(IngestionJobStatus.READY);
    job.setCompletedAt(completedAt);
    job.setVersion(job.getVersion() + 1);
  }

  /**
   * CAS 状态推进 PROCESSING → FAILED，写入失败分类与安全短摘要.
   *
   * @param job 当前读取的 Job（携带版本）
   * @param completedAt 进入 FAILED 的时间
   * @param failureCategory 失败分类（安全枚举名）
   * @param failureMessage 失败安全短摘要
   * @throws IngestionJobConflictException affected == 0
   */
  public void markFailed(
      IngestionJob job, LocalDateTime completedAt, String failureCategory, String failureMessage) {
    int affected =
        ingestionJobRepository.tryMarkFailed(
            job.getDocId(),
            job.getOperationVersion(),
            job.getVersion(),
            completedAt,
            failureCategory,
            failureMessage);
    if (affected == 0) {
      throw new IngestionJobConflictException(
          job.getDocId(), job.getOperationVersion(), "markFailed CAS failed: job already advanced");
    }
    job.setStatus(IngestionJobStatus.FAILED);
    job.setCompletedAt(completedAt);
    job.setFailureCategory(failureCategory);
    job.setFailureMessage(failureMessage);
    job.setVersion(job.getVersion() + 1);
  }

  /** 统计指定 KB 下处于给定状态的 Job 数量，供 smoke 诊断. */
  public long countByKnowledgeBaseIdAndStatus(long knowledgeBaseId, IngestionJobStatus status) {
    return ingestionJobRepository.countByKnowledgeBaseIdAndStatus(knowledgeBaseId, status);
  }
}
