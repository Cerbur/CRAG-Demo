package ai.cerbur.crag.knowledge.core.ingestion;

/**
 * 状态机决策结果分类（plan_21/21.3）。
 *
 * <ul>
 *   <li>{@link #APPLIED}：合法迁移，调用方应写库；
 *   <li>{@link #ACKNOWLEDGED}：重复事件（同状态自环或重复终态），合法但无需写库，事件 ACK；
 *   <li>{@link #REJECTED}：矛盾终态或非法倒退（如终态后回 PROCESSING），不覆盖事实，事件仍 ACK 但记录指标。
 * </ul>
 */
public enum IngestionTransitionOutcome {
  APPLIED,
  ACKNOWLEDGED,
  REJECTED
}
