package ai.cerbur.crag.knowledge.core.ingestion;

import java.util.Objects;

/**
 * {@link IngestionApplyService#apply} 的结果（plan_21/21.3），驱动 handler 的 ACK/重试/DLQ 决策。
 *
 * <ul>
 *   <li>{@link #acknowledged()}：事件被确认但未写库（旧版本、重复事件、矛盾终态）。handler 应 ACK。
 *   <li>{@link #applied()}：状态已写库。handler 应 ACK。
 *   <li>{@link #retryable()}：瞬时失败（文档未找到、CAS 冲突、瞬时异常）。handler 应保留 Pending 等 reclaim。
 *   <li>{@link #rejected()}：Tenant/KB/doc 不一致或非法事件——不可重试，handler 应 DLQ。
 * </ul>
 */
public record IngestionApplyResult(Decision decision, String reason) {

  public enum Decision {
    ACKNOWLEDGED,
    APPLIED,
    RETRYABLE,
    REJECTED
  }

  public IngestionApplyResult {
    Objects.requireNonNull(decision, "decision");
    if (reason == null || reason.isBlank()) {
      throw new IllegalArgumentException("reason must not be blank");
    }
  }

  public static IngestionApplyResult acknowledged(String reason) {
    return new IngestionApplyResult(Decision.ACKNOWLEDGED, reason);
  }

  public static IngestionApplyResult applied(String reason) {
    return new IngestionApplyResult(Decision.APPLIED, reason);
  }

  public static IngestionApplyResult retryable(String reason) {
    return new IngestionApplyResult(Decision.RETRYABLE, reason);
  }

  public static IngestionApplyResult rejected(String reason) {
    return new IngestionApplyResult(Decision.REJECTED, reason);
  }

  /** 是否应当写库（仅 APPLIED）。 */
  public boolean applied() {
    return decision == Decision.APPLIED;
  }
}
