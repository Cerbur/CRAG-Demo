package ai.cerbur.crag.knowledge.reconcile;

/**
 * Reconciler 对单个滞留文档的处理结果分类（plan_21/21.5）。
 *
 * <ul>
 *   <li>{@link #NO_ACTION} — RAG 状态与 Knowledge 一致，无需修复；
 *   <li>{@link #REPAIRED} — 按 RAG 权威状态修复了 Knowledge 投影；
 *   <li>{@link #TIMED_OUT} — RAG 滞留 PROCESSING 已终态化为安全超时失败（后续轮次按 retry 策略处理）；
 *   <li>{@link #RETRIED} — 创建了新 operationVersion 并发布 DOC_UPLOADED（自动 retry）；
 *   <li>{@link #RAG_UNAVAILABLE} — RAG Status RPC 不可用，本轮跳过；
 *   <li>{@link #CONFLICT} — Document CAS 冲突，另一实例已接管。
 * </ul>
 */
public enum ReconcileOutcome {
  NO_ACTION,
  REPAIRED,
  TIMED_OUT,
  RETRIED,
  RAG_UNAVAILABLE,
  CONFLICT
}
