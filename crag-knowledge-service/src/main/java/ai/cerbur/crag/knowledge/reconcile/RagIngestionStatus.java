package ai.cerbur.crag.knowledge.reconcile;

import java.util.Objects;

/**
 * RAG Ingestion Status 投影（plan_21/21.5）—— {@link RagIngestionStatusClient} 的返回值。
 *
 * <p>从 RAG gRPC {@code IngestionStatusView} 映射而来，字段经安全限长，不泄漏堆栈/SQL。Reconciler 据此决策修复、终态化或重试。
 *
 * @param operationVersion 文档操作版本
 * @param status 展示值：PENDING / PROCESSING / READY / FAILED / SUPERSEDED
 * @param attempt RAG Job 当前 attempt 序号
 * @param jobId RAG Ingestion Job 本地 ID
 * @param failureCategory 失败分类（安全枚举名）；可空
 * @param failureMessage 失败安全短摘要；可空
 * @param startedAtEpochMillis 进入 PROCESSING 的时间（epoch millis）；可空
 * @param completedAtEpochMillis 进入终态的时间（epoch millis）；可空
 */
public record RagIngestionStatus(
    long operationVersion,
    String status,
    int attempt,
    long jobId,
    String failureCategory,
    String failureMessage,
    Long startedAtEpochMillis,
    Long completedAtEpochMillis) {

  public RagIngestionStatus {
    Objects.requireNonNull(status, "status");
  }
}
