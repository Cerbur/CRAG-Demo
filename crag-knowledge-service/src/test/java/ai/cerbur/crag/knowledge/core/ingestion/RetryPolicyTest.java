package ai.cerbur.crag.knowledge.core.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * RetryPolicy 纯函数参数化测试（plan_21/21.5）。
 *
 * <p>验证设计事实来源（§8.3）的四类可重试分类、退避 30s/120s、attempt 上限 3、确定性错误与未分类不可重试。
 */
@DisplayName("RetryPolicy")
class RetryPolicyTest {

  private final RetryPolicy policy = new RetryPolicy();

  // --- 可重试四分类：attempt 1 → 退避 30s；attempt 2 → 退避 120s ---

  @ParameterizedTest(name = "可重试分类 {0} attempt=1 → retryable 退避 30s")
  @ValueSource(
      strings = {
        "DISPATCH_MISSING",
        "FILE_READ_FAILED",
        "PROCESSING_TIMEOUT",
        "INDEX_TRANSIENT_FAILURE"
      })
  void retryableCategoryAttemptOneBacksOff30Seconds(String category) {
    RetryDecision decision = policy.decide(category, 1);

    assertThat(decision.retryable()).isTrue();
    assertThat(decision.delay()).isEqualTo(Duration.ofSeconds(30));
    assertThat(decision.reason()).contains(category);
  }

  @ParameterizedTest(name = "可重试分类 {0} attempt=2 → retryable 退避 120s")
  @ValueSource(
      strings = {
        "DISPATCH_MISSING",
        "FILE_READ_FAILED",
        "PROCESSING_TIMEOUT",
        "INDEX_TRANSIENT_FAILURE"
      })
  void retryableCategoryAttemptTwoBacksOff120Seconds(String category) {
    RetryDecision decision = policy.decide(category, 2);

    assertThat(decision.retryable()).isTrue();
    assertThat(decision.delay()).isEqualTo(Duration.ofSeconds(120));
    assertThat(decision.reason()).contains(category);
  }

  // --- attempt 上限 3 截止 ---

  @ParameterizedTest(name = "可重试分类 {0} attempt=3 → 不可重试（达上限）")
  @ValueSource(
      strings = {
        "DISPATCH_MISSING",
        "FILE_READ_FAILED",
        "PROCESSING_TIMEOUT",
        "INDEX_TRANSIENT_FAILURE"
      })
  void retryableCategoryAttemptThreeExhausted(String category) {
    RetryDecision decision = policy.decide(category, 3);

    assertThat(decision.retryable()).isFalse();
    assertThat(decision.delay()).isEqualTo(Duration.ZERO);
    assertThat(decision.reason()).contains("exhausted");
  }

  @ParameterizedTest(name = "可重试分类 {0} attempt>3 → 不可重试")
  @ValueSource(
      strings = {
        "DISPATCH_MISSING",
        "FILE_READ_FAILED",
        "PROCESSING_TIMEOUT",
        "INDEX_TRANSIENT_FAILURE"
      })
  void retryableCategoryAttemptBeyondThreeNotRetryable(String category) {
    RetryDecision decision = policy.decide(category, 4);

    assertThat(decision.retryable()).isFalse();
  }

  // --- 确定性错误不可重试 ---

  @ParameterizedTest(name = "确定性错误 {0} 不可重试")
  @ValueSource(
      strings = {
        "CHECKSUM_MISMATCH",
        "FILE_SIZE_MISMATCH",
        "UNSUPPORTED_FILE_TYPE",
        "UTF8_DECODE_FAILED",
        "CONTENT_EMPTY",
        "CHUNK_SPLIT_FAILED"
      })
  void deterministicFailureNotRetryable(String category) {
    RetryDecision decision = policy.decide(category, 1);

    assertThat(decision.retryable()).isFalse();
    assertThat(decision.delay()).isEqualTo(Duration.ZERO);
    assertThat(decision.reason()).contains("deterministic");
  }

  // --- 未分类默认不可重试 ---

  @ParameterizedTest(name = "未知分类 {0} 默认不可重试")
  @ValueSource(strings = {"UNKNOWN_ERROR", "", "SOMETHING_NEW"})
  void unknownCategoryNotRetryable(String category) {
    RetryDecision decision = policy.decide(category, 1);

    assertThat(decision.retryable()).isFalse();
    assertThat(decision.reason()).contains("unknown");
  }

  // --- null 分类不可重试（安全默认） ---

  @org.junit.jupiter.api.Test
  @DisplayName("null 分类 → 不可重试（安全默认）")
  void nullCategoryNotRetryable() {
    RetryDecision decision = policy.decide(null, 1);

    assertThat(decision.retryable()).isFalse();
  }

  // --- 退避序列完整：attempt 1/2/3 依次为 30s/120s/exhausted ---

  @ParameterizedTest(name = "PROCESSING_TIMEOUT attempt={0} → delay={1}s retryable={2}")
  @CsvSource({"1, 30, true", "2, 120, true", "3, 0, false"})
  void backoffSequenceForProcessingTimeout(int attempt, long expectedSeconds, boolean retryable) {
    RetryDecision decision = policy.decide("PROCESSING_TIMEOUT", attempt);

    assertThat(decision.retryable()).isEqualTo(retryable);
    assertThat(decision.delay()).isEqualTo(Duration.ofSeconds(expectedSeconds));
  }
}
