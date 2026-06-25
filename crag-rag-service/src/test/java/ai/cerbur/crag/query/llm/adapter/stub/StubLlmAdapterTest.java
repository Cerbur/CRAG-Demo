package ai.cerbur.crag.query.llm.adapter.stub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.cerbur.crag.query.llm.config.QueryProperties;
import ai.cerbur.crag.query.llm.config.QueryProperties.Llm;
import ai.cerbur.crag.query.llm.config.QueryProperties.Provider;
import ai.cerbur.crag.query.llm.config.QueryProperties.Stub;
import ai.cerbur.crag.query.llm.config.QueryProperties.StubMode;
import ai.cerbur.crag.query.llm.contract.LlmFailureCategory;
import ai.cerbur.crag.query.llm.contract.LlmProviderException;
import ai.cerbur.crag.query.llm.contract.LlmRequest;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** StubLlmAdapter 单元测试 —— 验证 success/failure 两种模式的确定性行为. */
@DisplayName("StubLlmAdapter")
class StubLlmAdapterTest {

  private static final String FIXED_ANSWER = "已根据知识库证据生成回答。[S1]";

  private static QueryProperties propsWithMode(StubMode mode) {
    QueryProperties props = new QueryProperties();
    props.setLlm(new Llm(Provider.STUB, Duration.ofSeconds(120), null, new Stub(mode)));
    return props;
  }

  private static LlmRequest validRequest() {
    return new LlmRequest("system", "user", 3);
  }

  // ================================================================
  // SUCCESS 模式
  // ================================================================

  @Nested
  @DisplayName("SUCCESS 模式")
  class SuccessMode {

    @Test
    @DisplayName("返回固定回答且 usage 为 null")
    void returnsFixedAnswerWithNullUsage() {
      var adapter = new StubLlmAdapter(propsWithMode(StubMode.SUCCESS));
      var result = adapter.generate(validRequest());
      assertThat(result.answer()).isEqualTo(FIXED_ANSWER);
      assertThat(result.usage()).isNull();
    }

    @Test
    @DisplayName("sourceCount = 1 时正常返回（最小有效值）")
    void sourceCountOne() {
      var adapter = new StubLlmAdapter(propsWithMode(StubMode.SUCCESS));
      var result = adapter.generate(new LlmRequest("system", "user", 1));
      assertThat(result.answer()).isEqualTo(FIXED_ANSWER);
    }

    @Test
    @DisplayName("sourceCount = N (N > 1) 时正常返回")
    void sourceCountGreaterThanOne() {
      var adapter = new StubLlmAdapter(propsWithMode(StubMode.SUCCESS));
      var result = adapter.generate(new LlmRequest("system", "user", 5));
      assertThat(result.answer()).isEqualTo(FIXED_ANSWER);
    }

    @Test
    @DisplayName("相同输入输出完全确定（多次调用结果一致）")
    void deterministic() {
      var adapter = new StubLlmAdapter(propsWithMode(StubMode.SUCCESS));
      var result1 = adapter.generate(validRequest());
      var result2 = adapter.generate(validRequest());
      assertThat(result1.answer()).isEqualTo(result2.answer());
      assertThat(result1.usage()).isNull();
      assertThat(result2.usage()).isNull();
    }

    @Test
    @DisplayName("不解析系统提示词或用户提示词")
    void ignoresPromptContent() {
      var adapter = new StubLlmAdapter(propsWithMode(StubMode.SUCCESS));
      var result1 = adapter.generate(new LlmRequest("prompt A", "question 1", 2));
      var result2 = adapter.generate(new LlmRequest("prompt B", "question 2", 2));
      assertThat(result1.answer()).isEqualTo(result2.answer());
    }
  }

  // ================================================================
  // FAILURE 模式
  // ================================================================

  @Nested
  @DisplayName("FAILURE 模式")
  class FailureMode {

    @Test
    @DisplayName("抛出 LlmProviderException 分类为 UNKNOWN")
    void throwsLlmProviderExceptionWithUnknown() {
      var adapter = new StubLlmAdapter(propsWithMode(StubMode.FAILURE));
      assertThatThrownBy(() -> adapter.generate(validRequest()))
          .isInstanceOf(LlmProviderException.class)
          .satisfies(
              ex -> {
                var lpe = (LlmProviderException) ex;
                assertThat(lpe.getCategory()).isEqualTo(LlmFailureCategory.UNKNOWN);
              });
    }

    @Test
    @DisplayName("无论什么请求都抛出异常")
    void alwaysThrows() {
      var adapter = new StubLlmAdapter(propsWithMode(StubMode.FAILURE));
      assertThatThrownBy(() -> adapter.generate(validRequest()))
          .isInstanceOf(LlmProviderException.class);
      assertThatThrownBy(() -> adapter.generate(new LlmRequest("x", "y", 1)))
          .isInstanceOf(LlmProviderException.class);
    }

    @Test
    @DisplayName("providerMessage 为 'Stub failure mode'")
    void providerMessage() {
      var adapter = new StubLlmAdapter(propsWithMode(StubMode.FAILURE));
      assertThatThrownBy(() -> adapter.generate(validRequest()))
          .isInstanceOf(LlmProviderException.class)
          .satisfies(
              ex -> {
                var lpe = (LlmProviderException) ex;
                assertThat(lpe.getProviderMessage()).isEqualTo("Stub failure mode");
              });
    }

    @Test
    @DisplayName("cause 为 null")
    void causeIsNull() {
      var adapter = new StubLlmAdapter(propsWithMode(StubMode.FAILURE));
      assertThatThrownBy(() -> adapter.generate(validRequest()))
          .isInstanceOf(LlmProviderException.class)
          .satisfies(
              ex -> {
                var lpe = (LlmProviderException) ex;
                assertThat(lpe.getCause()).isNull();
              });
    }
  }

  // ================================================================
  // 构造与默认值
  // ================================================================

  @Nested
  @DisplayName("构造与默认值")
  class Construction {

    @Test
    @DisplayName("null Stub.mode 回退为 SUCCESS")
    void nullStubModeDefaultsToSuccess() {
      QueryProperties props = new QueryProperties();
      props.setLlm(new Llm(Provider.STUB, Duration.ofSeconds(120), null, new Stub(null)));
      var adapter = new StubLlmAdapter(props);
      var result = adapter.generate(validRequest());
      assertThat(result.answer()).isEqualTo(FIXED_ANSWER);
    }
  }
}
