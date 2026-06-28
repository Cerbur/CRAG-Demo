package ai.cerbur.crag.knowledge.reconcile;

import java.util.List;
import java.util.Objects;

/**
 * Reconciler 单批扫描汇总（plan_21/21.5）。
 *
 * @param scanned 本批扫描的滞留候选数
 * @param results 每个候选的处理结果
 */
public record ReconcileSummary(int scanned, List<ReconcileItemResult> results) {

  public ReconcileSummary {
    Objects.requireNonNull(results, "results");
  }

  /** 统计指定分类的次数。 */
  public long countBy(ReconcileOutcome outcome) {
    return results.stream().filter(r -> r.outcome() == outcome).count();
  }
}
