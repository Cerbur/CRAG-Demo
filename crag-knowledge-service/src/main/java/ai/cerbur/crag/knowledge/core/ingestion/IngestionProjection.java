package ai.cerbur.crag.knowledge.core.ingestion;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Document 在单一 operationVersion 上的摄取投影（plan_21/21.3）。
 *
 * <p>{@code jobId}、失败字段与时间字段均可为 null：PENDING 时全空，PROCESSING 填 startedAt，READY/FAILED 填 completedAt。
 *
 * @param operationVersion 文档逻辑操作版本；与行级 CAS {@code version} 不同
 * @param attempt 本版本内已使用的尝试序号（首次上传固定 0；PROCESSING/READY/FAILED 事件回填实际值）
 * @param jobId RAG Ingestion Job 的本地 ID；可空
 * @param status 当前状态
 * @param failureCategory RAG 给出的失败分类（如 {@code CHECKSUM_MISMATCH}）；可空
 * @param failureMessage 安全限长后的失败描述，不泄漏堆栈/SQL；可空
 * @param startedAt 本版本 PROCESSING 起始时间；可空
 * @param completedAt 本版本进入终态的时间；可空
 * @param nextRetryAt 计算出的下次重试时间（21.5 填写）；可空
 * @param version 行级 CAS 版本；apply 调用方用于 WHERE 条件
 */
public record IngestionProjection(
    long operationVersion,
    int attempt,
    Long jobId,
    IngestionStatus status,
    String failureCategory,
    String failureMessage,
    LocalDateTime startedAt,
    LocalDateTime completedAt,
    LocalDateTime nextRetryAt,
    Long version) {

  public IngestionProjection {
    Objects.requireNonNull(status, "status");
  }

  /** 简化构造：不含 CAS version（适用于从事件构建目标投影）。 */
  public IngestionProjection(
      long operationVersion,
      int attempt,
      Long jobId,
      IngestionStatus status,
      String failureCategory,
      String failureMessage,
      LocalDateTime startedAt,
      LocalDateTime completedAt,
      LocalDateTime nextRetryAt) {
    this(
        operationVersion,
        attempt,
        jobId,
        status,
        failureCategory,
        failureMessage,
        startedAt,
        completedAt,
        nextRetryAt,
        null);
  }
}
