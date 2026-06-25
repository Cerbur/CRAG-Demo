package ai.cerbur.crag.query.llm.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * LLM contract 类型单元测试 —— 验证 LlmRequest、LlmResult、LlmUsage、LlmProviderException 和 LlmFailureCategory
 * 的不变量.
 */
@DisplayName("LLM contract 类型")
class LlmContractTest {

  // ================================================================
  // LlmRequest
  // ================================================================

  @Nested
  @DisplayName("LlmRequest")
  class LlmRequestTest {

    @Test
    @DisplayName("合法请求创建成功")
    void validRequest() {
      var req = new LlmRequest("system", "user", 3);
      assertThat(req.systemPrompt()).isEqualTo("system");
      assertThat(req.userPrompt()).isEqualTo("user");
      assertThat(req.sourceCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("systemPrompt 为 null 抛出 IllegalArgumentException")
    void nullSystemPrompt() {
      assertThatThrownBy(() -> new LlmRequest(null, "user", 1))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("systemPrompt");
    }

    @Test
    @DisplayName("systemPrompt 为 blank 抛出 IllegalArgumentException")
    void blankSystemPrompt() {
      assertThatThrownBy(() -> new LlmRequest("  ", "user", 1))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("systemPrompt");
    }

    @Test
    @DisplayName("userPrompt 为 null 抛出 IllegalArgumentException")
    void nullUserPrompt() {
      assertThatThrownBy(() -> new LlmRequest("system", null, 1))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("userPrompt");
    }

    @Test
    @DisplayName("userPrompt 为 blank 抛出 IllegalArgumentException")
    void blankUserPrompt() {
      assertThatThrownBy(() -> new LlmRequest("system", "", 1))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("userPrompt");
    }

    @Test
    @DisplayName("sourceCount = 0 抛出 IllegalArgumentException")
    void zeroSourceCount() {
      assertThatThrownBy(() -> new LlmRequest("system", "user", 0))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("sourceCount");
    }

    @Test
    @DisplayName("sourceCount 为负值抛出 IllegalArgumentException")
    void negativeSourceCount() {
      assertThatThrownBy(() -> new LlmRequest("system", "user", -1))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("sourceCount");
    }
  }

  // ================================================================
  // LlmResult
  // ================================================================

  @Nested
  @DisplayName("LlmResult")
  class LlmResultTest {

    @Test
    @DisplayName("合法结果创建成功")
    void validResult() {
      var result = new LlmResult("answer", null);
      assertThat(result.answer()).isEqualTo("answer");
      assertThat(result.usage()).isNull();
    }

    @Test
    @DisplayName("answer 为 null 抛出 IllegalArgumentException")
    void nullAnswer() {
      assertThatThrownBy(() -> new LlmResult(null, null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("answer");
    }

    @Test
    @DisplayName("answer 为 blank 抛出 IllegalArgumentException")
    void blankAnswer() {
      assertThatThrownBy(() -> new LlmResult("  ", null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("answer");
    }

    @Test
    @DisplayName("可携带 usage 信息")
    void withUsage() {
      var usage = new LlmUsage(10, 20, null);
      var result = new LlmResult("answer", usage);
      assertThat(result.usage()).isSameAs(usage);
    }
  }

  // ================================================================
  // LlmUsage
  // ================================================================

  @Nested
  @DisplayName("LlmUsage")
  class LlmUsageTest {

    @Test
    @DisplayName("所有字段为 null")
    void allNull() {
      var usage = new LlmUsage(null, null, null);
      assertThat(usage.inputTokens()).isNull();
      assertThat(usage.outputTokens()).isNull();
      assertThat(usage.thinkingTokens()).isNull();
    }

    @Test
    @DisplayName("部分字段有值")
    void partialPopulation() {
      var usage = new LlmUsage(100, null, 5);
      assertThat(usage.inputTokens()).isEqualTo(100);
      assertThat(usage.outputTokens()).isNull();
      assertThat(usage.thinkingTokens()).isEqualTo(5);
    }

    @Test
    @DisplayName("全部字段有值")
    void fullPopulation() {
      var usage = new LlmUsage(100, 50, 10);
      assertThat(usage.inputTokens()).isEqualTo(100);
      assertThat(usage.outputTokens()).isEqualTo(50);
      assertThat(usage.thinkingTokens()).isEqualTo(10);
    }

    @Test
    @DisplayName("不将 null 伪造为 0")
    void noFakeZero() {
      var usage = new LlmUsage(null, null, null);
      assertThat(usage.inputTokens()).isNotEqualTo(Integer.valueOf(0));
    }
  }

  // ================================================================
  // LlmProviderException
  // ================================================================

  @Nested
  @DisplayName("LlmProviderException")
  class LlmProviderExceptionTest {

    @Test
    @DisplayName("构造并获取分类")
    void constructionAndCategory() {
      var ex = new LlmProviderException(LlmFailureCategory.AUTHENTICATION, "invalid key", null);
      assertThat(ex.getCategory()).isEqualTo(LlmFailureCategory.AUTHENTICATION);
      assertThat(ex.getProviderMessage()).isEqualTo("invalid key");
    }

    @Test
    @DisplayName("providerMessage 可为 null")
    void nullableProviderMessage() {
      var ex = new LlmProviderException(LlmFailureCategory.TIMEOUT, null, null);
      assertThat(ex.getProviderMessage()).isNull();
    }

    @Test
    @DisplayName("cause 可为 null")
    void nullableCause() {
      var ex = new LlmProviderException(LlmFailureCategory.SERVER_ERROR, "500", null);
      assertThat(ex.getCause()).isNull();
    }

    @Test
    @DisplayName("异常消息包含分类但无 providerMessage 泄露")
    void messageContainsCategoryOnly() {
      var ex = new LlmProviderException(LlmFailureCategory.RATE_LIMITED, null, null);
      assertThat(ex.getMessage()).contains("RATE_LIMITED");
    }

    @Test
    @DisplayName("异常消息不包含 providerMessage")
    void messageDoesNotContainProviderMessage() {
      var ex = new LlmProviderException(LlmFailureCategory.RATE_LIMITED, "too many requests", null);
      assertThat(ex.getMessage()).doesNotContain("too many requests");
    }

    @Test
    @DisplayName("异常消息不包含 API 密钥")
    void noApiKeyInMessage() {
      var ex =
          new LlmProviderException(
              LlmFailureCategory.AUTHENTICATION, "invalid sk-secret-key", null);
      assertThat(ex.getMessage()).doesNotContain("sk-secret-key");
    }

    @Test
    @DisplayName("cause 可传入")
    void withCause() {
      var cause = new RuntimeException("root");
      var ex = new LlmProviderException(LlmFailureCategory.UNKNOWN, null, cause);
      assertThat(ex.getCause()).isSameAs(cause);
    }
  }

  // ================================================================
  // LlmFailureCategory
  // ================================================================

  @Nested
  @DisplayName("LlmFailureCategory")
  class LlmFailureCategoryTest {

    @Test
    @DisplayName("包含全部 8 个枚举值")
    void allValues() {
      assertThat(LlmFailureCategory.values())
          .containsExactlyInAnyOrder(
              LlmFailureCategory.AUTHENTICATION,
              LlmFailureCategory.RATE_LIMITED,
              LlmFailureCategory.TIMEOUT,
              LlmFailureCategory.PROTOCOL,
              LlmFailureCategory.EMPTY_RESPONSE,
              LlmFailureCategory.TRUNCATED_RESPONSE,
              LlmFailureCategory.SERVER_ERROR,
              LlmFailureCategory.UNKNOWN);
    }
  }
}
