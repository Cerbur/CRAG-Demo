package ai.cerbur.crag.knowledge.core.ingestion;

/**
 * 单 operationVersion 内 Document 摄取状态迁移决策（plan_21/21.3）。
 *
 * <p>合法迁移：
 *
 * <pre>
 * PENDING    -> PROCESSING / READY / FAILED / PENDING
 * PROCESSING -> PROCESSING / READY / FAILED
 * READY      -> READY            （重复终态 ACK，不覆盖）
 * FAILED     -> FAILED           （重复终态 ACK，不覆盖）
 * </pre>
 *
 * <p>READY 与 FAILED 互斥（首个终态获胜，矛盾终态 REJECTED）；终态后回到 PROCESSING 或 PENDING 拒绝。新 operationVersion 由
 * {@link IngestionApplyService} 在比对版本后短路 ACK，不会进入本状态机。
 *
 * <p>本类是纯函数，无副作用，便于表驱动测试。
 */
public final class IngestionStateMachine {

  private IngestionStateMachine() {}

  /**
   * 判定事件相对当前状态的迁移结果。
   *
   * @param current 数据库当前状态
   * @param event 事件携带的目标状态
   */
  public static IngestionTransitionDecision decide(IngestionStatus current, IngestionStatus event) {
    if (current == IngestionStatus.READY || current == IngestionStatus.FAILED) {
      if (event == current) {
        return IngestionTransitionDecision.acknowledged(
            "duplicate terminal status " + current + " acknowledged");
      }
      return IngestionTransitionDecision.rejected(
          "contradictory transition rejected: current=" + current + " event=" + event);
    }
    if (event == IngestionStatus.PENDING && current != IngestionStatus.PENDING) {
      return IngestionTransitionDecision.rejected("cannot regress to PENDING from " + current);
    }
    // current 为 PENDING 或 PROCESSING，event 为 PROCESSING/READY/FAILED（或 PENDING 自环）均合法。
    return IngestionTransitionDecision.applied(
        "legal transition applied: current=" + current + " event=" + event);
  }
}
