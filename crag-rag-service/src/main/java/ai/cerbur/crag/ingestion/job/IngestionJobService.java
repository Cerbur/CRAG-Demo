package ai.cerbur.crag.ingestion.job;

import ai.cerbur.crag.storage.IngestionJobConflictException;
import ai.cerbur.crag.storage.IngestionJobDao;
import ai.cerbur.crag.storage.entity.IngestionJob;
import ai.cerbur.crag.storage.entity.IngestionJobStatus;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Ingestion Job 编排服务（Plan 19）—— 管理消费 {@code DOC_UPLOADED} 后 Job 的幂等创建与状态机推进.
 *
 * <p>状态机：{@code PENDING → PROCESSING → READY / FAILED}。
 *
 * <ul>
 *   <li>{@link #resolve} 幂等创建或定位 Job，并决定是否继续处理（双层幂等：processed_event + 业务键）；
 *   <li>{@link #markProcessing} CAS 推进 PENDING → PROCESSING；
 *   <li>{@link #markReady} / {@link #markFailed} 推进至终态。
 * </ul>
 *
 * <p>文件读取、校验与切分由 19.3 的编排扩展；状态事件发布由 19.6 接入。本类只持有状态机与幂等决策，CAS 冲突视为并发幂等结果， WARN 记录后不重试.
 *
 * @since 2026-06-27
 */
@Service
public class IngestionJobService {

  private static final Logger log = LoggerFactory.getLogger(IngestionJobService.class);

  @Autowired private IngestionJobDao ingestionJobDao;

  /**
   * 幂等创建或定位 Job，并给出是否继续处理的决策.
   *
   * <p>首次见到 {@code (docId, operationVersion)} 时创建 PENDING Job，{@code fresh=true}、{@code
   * needsProcessing=true}。 重复事件命中已有 Job：仍为 PENDING 时 {@code needsProcessing=true}（可继续推进）；已为
   * PROCESSING / READY / FAILED 时 {@code needsProcessing=false}（消费层视为已处理，不重复建 Job 或写 Chunk）.
   *
   * @param tenantId 租户 ID
   * @param knowledgeBaseId 知识库 ID
   * @param docId 文档 ID
   * @param operationVersion 文档操作版本
   * @param fileType 文件类型展示值
   * @param sizeBytes 文件字节数
   * @param sha256 文件 sha256
   * @return 解析结果
   */
  public IngestionJobResolution resolve(
      long tenantId,
      long knowledgeBaseId,
      long docId,
      long operationVersion,
      String fileType,
      long sizeBytes,
      String sha256) {
    IngestionJob existing =
        ingestionJobDao.findByDocIdAndOperationVersion(docId, operationVersion).orElse(null);
    if (existing != null) {
      log.info(
          "Ingestion job already exists — docId={} operationVersion={} status={}",
          docId,
          operationVersion,
          existing.getStatus());
      return new IngestionJobResolution(
          existing, false, existing.getStatus() == IngestionJobStatus.PENDING);
    }

    IngestionJob job =
        ingestionJobDao.findOrCreate(
            tenantId, knowledgeBaseId, docId, operationVersion, fileType, sizeBytes, sha256);
    boolean fresh = job.getStatus() == IngestionJobStatus.PENDING;
    if (!fresh) {
      // findOrCreate 在并发下可能返回他人刚建的 Job，按其实际状态决策。
      log.info(
          "Ingestion job resolved to existing after concurrent create — docId={} status={}",
          docId,
          job.getStatus());
    }
    return new IngestionJobResolution(job, fresh, job.getStatus() == IngestionJobStatus.PENDING);
  }

  /**
   * CAS 推进 PENDING → PROCESSING.
   *
   * <p>并发下另一实例已推进时抛出 {@link IngestionJobConflictException}；调用方按业务语义视为已处理.
   *
   * @param job 当前 Job
   * @throws IngestionJobConflictException 状态或版本已被其他实例变更
   */
  public void markProcessing(IngestionJob job) {
    ingestionJobDao.markProcessing(job, LocalDateTime.now());
    log.info(
        "Ingestion job PROCESSING — docId={} operationVersion={}",
        job.getDocId(),
        job.getOperationVersion());
  }

  /** CAS 推进 PROCESSING → READY. */
  public void markReady(IngestionJob job) {
    ingestionJobDao.markReady(job, LocalDateTime.now());
    log.info(
        "Ingestion job READY — docId={} operationVersion={}",
        job.getDocId(),
        job.getOperationVersion());
  }

  /**
   * CAS 推进 PROCESSING → FAILED，记录安全失败分类与短摘要.
   *
   * @param job 当前 Job
   * @param category 失败分类
   * @param message 失败安全短摘要（禁止透传敏感信息）
   */
  public void markFailed(IngestionJob job, IngestionJobFailureCategory category, String message) {
    String safeMessage = sanitizeFailureMessage(message);
    ingestionJobDao.markFailed(job, LocalDateTime.now(), category.name(), safeMessage);
    log.warn(
        "Ingestion job FAILED — docId={} operationVersion={} category={}",
        job.getDocId(),
        job.getOperationVersion(),
        category);
  }

  /**
   * 收敛失败摘要为安全短字符串：去换行、限长，避免透传堆栈或文件内容片段.
   *
   * @param message 原始摘要
   * @return 安全短摘要
   */
  static String sanitizeFailureMessage(String message) {
    if (message == null) {
      return IngestionJobFailureCategory.UNKNOWN.name();
    }
    String oneLine = message.replace("\r", " ").replace("\n", " ").trim();
    if (oneLine.isBlank()) {
      return IngestionJobFailureCategory.UNKNOWN.name();
    }
    return oneLine.length() > 200 ? oneLine.substring(0, 200) : oneLine;
  }
}
