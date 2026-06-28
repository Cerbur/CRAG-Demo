package ai.cerbur.crag.knowledge.core.ingestion;

import java.util.Objects;

/**
 * 状态机判定一个事件相对当前状态的结果（plan_21/21.3）。
 *
 * @param outcome 见 {@link IngestionTransitionOutcome}
 * @param reason 人类可读原因，用于日志和指标；不泄漏堆栈或字段值
 */
public record IngestionTransitionDecision(IngestionTransitionOutcome outcome, String reason) {

  public IngestionTransitionDecision {
    Objects.requireNonNull(outcome, "outcome");
    if (reason == null || reason.isBlank()) {
      throw new IllegalArgumentException("reason must not be blank");
    }
  }

  public static IngestionTransitionDecision applied(String reason) {
    return new IngestionTransitionDecision(IngestionTransitionOutcome.APPLIED, reason);
  }

  public static IngestionTransitionDecision acknowledged(String reason) {
    return new IngestionTransitionDecision(IngestionTransitionOutcome.ACKNOWLEDGED, reason);
  }

  public static IngestionTransitionDecision rejected(String reason) {
    return new IngestionTransitionDecision(IngestionTransitionOutcome.REJECTED, reason);
  }

  /** 是否应推进数据库投影。 */
  public boolean shouldApply() {
    return outcome == IngestionTransitionOutcome.APPLIED;
  }
}
