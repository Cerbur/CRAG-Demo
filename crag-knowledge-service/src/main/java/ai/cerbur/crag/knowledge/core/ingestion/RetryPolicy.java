package ai.cerbur.crag.knowledge.core.ingestion;

import java.time.Duration;
import java.util.Set;

/**
 * 摄取失败重试决策策略（plan_21/21.5）。
 *
 * <p>设计事实来源（{@code docs/superpowers/specs/2026-06-28-dual-api-and-ingestion-lifecycle-design.md}
 * §8.3）：
 *
 * <ul>
 *   <li>默认总 attempt 上限为 3（首次摄取计为 1）；
 *   <li>自动退避默认依次为 30 秒和 120 秒；
 *   <li>{@code DISPATCH_MISSING}、{@code FILE_READ_FAILED}、{@code PROCESSING_TIMEOUT} 和 {@code
 *       INDEX_TRANSIENT_FAILURE} 可重试；
 *   <li>checksum、size、file type、UTF-8 解码和内容/切分校验失败不可重试；
 *   <li>未分类错误默认不自动重试。
 * </ul>
 *
 * <p>本类是纯函数，无副作用，便于参数化测试。
 */
public final class RetryPolicy {

  /** 默认总 attempt 上限（首次摄取计为 1）。 */
  public static final int DEFAULT_MAX_ATTEMPTS = 3;

  /** 默认退避序列：30 秒、120 秒。 */
  public static final Duration[] DEFAULT_BACKOFFS = {
    Duration.ofSeconds(30), Duration.ofSeconds(120)
  };

  /** 可重试失败分类（与设计 §8.3 一致）。 */
  public static final Set<String> RETRYABLE_CATEGORIES =
      Set.of(
          "DISPATCH_MISSING", "FILE_READ_FAILED", "PROCESSING_TIMEOUT", "INDEX_TRANSIENT_FAILURE");

  /**
   * 确定性文件/内容错误分类（与设计 §8.3 一致）：要求重新上传，不自动重试。
   *
   * <p>未列入 {@link #RETRYABLE_CATEGORIES} 且未列入本集合的分类视为「未分类」，按设计默认不自动重试，原因标注 unknown。
   */
  public static final Set<String> DETERMINISTIC_CATEGORIES =
      Set.of(
          "CHECKSUM_MISMATCH",
          "FILE_SIZE_MISMATCH",
          "UNSUPPORTED_FILE_TYPE",
          "UTF8_DECODE_FAILED",
          "CONTENT_EMPTY",
          "CHUNK_SPLIT_FAILED");

  private final int maxAttempts;
  private final Duration[] backoffs;

  /** 使用默认上限与退避序列构造。 */
  public RetryPolicy() {
    this(DEFAULT_MAX_ATTEMPTS, DEFAULT_BACKOFFS);
  }

  /**
   * 自定义上限与退避序列构造（测试可注入更短退避）。
   *
   * @param maxAttempts 总 attempt 上限（首次摄取计为 1）
   * @param backoffs 退避序列；{@code backoffs[attempt-1]} 为 attempt 的退避
   */
  public RetryPolicy(int maxAttempts, Duration[] backoffs) {
    if (maxAttempts < 1) {
      throw new IllegalArgumentException("maxAttempts must be >= 1");
    }
    if (backoffs == null || backoffs.length < maxAttempts - 1) {
      throw new IllegalArgumentException("backoffs must contain at least maxAttempts-1 entries");
    }
    this.maxAttempts = maxAttempts;
    this.backoffs = backoffs.clone();
  }

  /**
   * 判定给定失败分类与当前 attempt 序号是否允许重试。
   *
   * @param failureCategory 失败分类（可空，视为未知）
   * @param currentAttempt 已使用的 attempt 序号（首次摄取为 1）
   * @return 重试决策
   */
  public RetryDecision decide(String failureCategory, int currentAttempt) {
    if (failureCategory == null || failureCategory.isBlank()) {
      return RetryDecision.notRetryable("unknown failure category, no auto retry");
    }
    if (!RETRYABLE_CATEGORIES.contains(failureCategory)) {
      if (DETERMINISTIC_CATEGORIES.contains(failureCategory)) {
        return RetryDecision.notRetryable(
            "deterministic failure category, requires re-upload: " + failureCategory);
      }
      return RetryDecision.notRetryable(
          "unknown failure category, no auto retry: " + failureCategory);
    }
    if (currentAttempt >= maxAttempts) {
      return RetryDecision.notRetryable(
          "retry exhausted: category=" + failureCategory + " attempts=" + currentAttempt);
    }
    // attempt 1 → backoffs[0] (30s)；attempt 2 → backoffs[1] (120s)。
    int index = Math.min(currentAttempt - 1, backoffs.length - 1);
    Duration delay = backoffs[index];
    return RetryDecision.retryable(delay, "retryable category=" + failureCategory);
  }
}
