package ai.cerbur.crag.ingestion.head;

import ai.cerbur.crag.storage.entity.IngestionJob;
import ai.cerbur.crag.storage.entity.IngestionJobStatus;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Ingestion status 投影（Plan 21.4）—— Provider 把它映射为 gRPC {@code IngestionStatusView}.
 *
 * @param tenantId 租户 ID
 * @param knowledgeBaseId 知识库 ID
 * @param docId 文档 ID
 * @param operationVersion 文档操作版本
 * @param status Job 状态展示值
 * @param attempt 保留字段（Knowledge 投影持有，RAG 侧暂未消费，固定 0）
 * @param jobId Job ID（数据库 IDENTITY，可能为 0 表示未持久化）
 * @param failureCategory 失败分类（安全枚举名）
 * @param failureMessage 失败安全短摘要
 * @param startedAt 进入 PROCESSING 的时间
 * @param completedAt 进入终态的时间
 */
public record IngestionStatusResult(
    long tenantId,
    long knowledgeBaseId,
    long docId,
    long operationVersion,
    IngestionJobStatus status,
    int attempt,
    long jobId,
    String failureCategory,
    String failureMessage,
    LocalDateTime startedAt,
    LocalDateTime completedAt) {

  public IngestionStatusResult {
    Objects.requireNonNull(status, "status");
  }

  /**
   * 从持久化 Job 构造投影.
   *
   * @param job 持久化 Job
   * @return 状态投影
   */
  public static IngestionStatusResult from(IngestionJob job) {
    return new IngestionStatusResult(
        job.getTenantId(),
        job.getKnowledgeBaseId(),
        job.getDocId(),
        job.getOperationVersion(),
        job.getStatus(),
        0,
        job.getJobId() == null ? 0L : job.getJobId(),
        job.getFailureCategory(),
        job.getFailureMessage(),
        job.getStartedAt(),
        job.getCompletedAt());
  }
}
