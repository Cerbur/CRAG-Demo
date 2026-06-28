package ai.cerbur.crag.knowledge.core.ingestion;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Knowledge 消费的 INGESTION_* 事件解析后的领域形式（plan_21/21.3）。
 *
 * <p>由 {@code IngestionStatusEventHandler} 从 RAG payload 解析得到，再交给 {@link IngestionApplyService} 应用到
 * Document 投影。 所有 ID 为 long（内部使用），跨进程边界均使用十进制字符串。{@code failureMessage} 在解析时已做安全限长。
 *
 * @param tenantId 事件归属租户
 * @param knowledgeBaseId 事件归属知识库
 * @param docId 文档 ID
 * @param operationVersion 本事件针对的 operationVersion
 * @param attempt RAG Job 当前 attempt 序号；可空（PROCESSING/READY 可能未填）
 * @param jobId RAG Ingestion Job 本地 ID；可空
 * @param targetStatus 目标状态（PROCESSING / READY / FAILED）
 * @param failureCategory 失败分类，仅 FAILED 携带；可空
 * @param failureMessage 安全限长后的失败描述；可空
 * @param startedAt RAG Job 起始时间；可空
 * @param completedAt RAG Job 完成时间；可空
 */
public record IngestionStatusEvent(
    long tenantId,
    long knowledgeBaseId,
    long docId,
    long operationVersion,
    Integer attempt,
    Long jobId,
    IngestionStatus targetStatus,
    String failureCategory,
    String failureMessage,
    LocalDateTime startedAt,
    LocalDateTime completedAt) {

  public IngestionStatusEvent {
    Objects.requireNonNull(targetStatus, "targetStatus");
    if (targetStatus == IngestionStatus.PENDING) {
      throw new IllegalArgumentException("INGESTION_* event must not target PENDING");
    }
  }
}
