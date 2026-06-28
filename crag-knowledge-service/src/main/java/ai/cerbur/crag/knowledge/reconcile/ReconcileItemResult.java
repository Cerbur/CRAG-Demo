package ai.cerbur.crag.knowledge.reconcile;

import java.util.Objects;

/**
 * Reconciler 对单个滞留文档的处理结果（plan_21/21.5）。
 *
 * @param docId 文档 ID
 * @param operationVersion 处理针对的 operationVersion
 * @param outcome 处理分类
 * @param reason 安全短摘要（日志/指标）
 */
public record ReconcileItemResult(
    long docId, long operationVersion, ReconcileOutcome outcome, String reason) {

  public ReconcileItemResult {
    Objects.requireNonNull(outcome, "outcome");
    Objects.requireNonNull(reason, "reason");
  }
}
