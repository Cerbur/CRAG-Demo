package ai.cerbur.crag.knowledge.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Knowledge 摄取恢复可观测计数器（plan_21/21.5）。
 *
 * <p>覆盖 retry 发起、retry 冲突、Reconciler 扫描/推进/重试/超时终态化与 RAG 不可用降级。计数器按需注册，业务调用方在关键路径递增；不记录完整 Token/API
 * Key 或文件内容。
 */
@Component
public class IngestionRecoveryMetrics {

  @Autowired private MeterRegistry registry;

  /** 记录一次 retry 发起（手动或自动）。 */
  public void retryIssued() {
    registry.counter("knowledge.ingestion.retry.issued").increment();
  }

  /** 记录一次 retry CAS 冲突（并发抢占失败）。 */
  public void retryConflict() {
    registry.counter("knowledge.ingestion.retry.conflict").increment();
  }

  /** 记录一次 retry 被拒绝（分类/上限/状态不允许）。 */
  public void retryRejected() {
    registry.counter("knowledge.ingestion.retry.rejected").increment();
  }

  /** 记录 Reconciler 一次批量扫描。 */
  public void reconcileScan() {
    registry.counter("knowledge.ingestion.reconcile.scan").increment();
  }

  /** 记录 Reconciler 扫描到的滞留候选数量。 */
  public void reconcileCandidates(int count) {
    registry.counter("knowledge.ingestion.reconcile.candidates").increment(count);
  }

  /** 记录 Reconciler 通过 RAG 权威状态修复投影的次数。 */
  public void reconcileRepaired() {
    registry.counter("knowledge.ingestion.reconcile.repaired").increment();
  }

  /** 记录 Reconciler 触发超时终态化（RAG CAS markTimedOut 成功）。 */
  public void reconcileTimedOut() {
    registry.counter("knowledge.ingestion.reconcile.timed_out").increment();
  }

  /** 记录 Reconciler 触发自动 retry（创建新版本 + DOC_UPLOADED）。 */
  public void reconcileRetried() {
    registry.counter("knowledge.ingestion.reconcile.retried").increment();
  }

  /** 记录 RAG Status RPC 不可用导致的降级跳过。 */
  public void reconcileRagUnavailable() {
    registry.counter("knowledge.ingestion.reconcile.rag_unavailable").increment();
  }
}
