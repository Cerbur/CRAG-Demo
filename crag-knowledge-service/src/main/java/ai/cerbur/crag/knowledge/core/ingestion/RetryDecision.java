package ai.cerbur.crag.knowledge.core.ingestion;

import java.time.Duration;
import java.util.Objects;

/**
 * RetryPolicy 的决策结果（plan_21/21.5）。
 *
 * <p>纯数据载体：{@code retryable=false} 时 {@code delay} 固定为 {@link Duration#ZERO}；{@code reason}
 * 携带安全短摘要，不泄漏堆栈 或文件内容。
 *
 * @param retryable 是否允许重试
 * @param delay 下一次重试前的退避时间；不可重试时为 {@link Duration#ZERO}
 * @param reason 决策原因（安全短摘要），用于日志/指标
 */
public record RetryDecision(boolean retryable, Duration delay, String reason) {

  public RetryDecision {
    Objects.requireNonNull(delay, "delay");
    Objects.requireNonNull(reason, "reason");
    if (!retryable && !delay.isZero()) {
      throw new IllegalArgumentException("non-retryable decision must have zero delay");
    }
  }

  /** 可重试决策工厂。 */
  public static RetryDecision retryable(Duration delay, String reason) {
    return new RetryDecision(true, delay, reason);
  }

  /** 不可重试决策工厂；delay 固定为 ZERO。 */
  public static RetryDecision notRetryable(String reason) {
    return new RetryDecision(false, Duration.ZERO, reason);
  }
}
