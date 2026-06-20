package ai.cerbur.crag.query;

import static org.assertj.core.api.Assertions.assertThat;

import ai.cerbur.crag.query.llm.config.DeepSeekApiKey;
import ai.cerbur.crag.query.llm.config.QueryLlmConfiguration;
import ai.cerbur.crag.query.llm.config.QueryProperties;
import ai.cerbur.crag.query.llm.config.QueryProperties.Provider;
import ai.cerbur.crag.query.llm.config.QueryProperties.StubMode;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * QueryProperties 配置绑定与校验单元测试.
 *
 * <p>验证默认值、合法范围、条件校验、枚举大小写不敏感和密钥脱敏.
 */
@DisplayName("QueryProperties 配置校验")
class QueryPropertiesTest {

  // ---- Utility ----

  private static ApplicationContextRunner runnerWith(String... properties) {
    return new ApplicationContextRunner()
        .withUserConfiguration(QueryLlmConfiguration.class)
        .withPropertyValues(properties);
  }

  // ============================================================
  // DeepSeekApiKey 单元
  // ============================================================

  @Nested
  @DisplayName("DeepSeekApiKey 值对象")
  class DeepSeekApiKeyUnit {

    @Test
    @DisplayName("toString() 掩码不泄露实际密钥")
    void toStringMasksValue() {
      DeepSeekApiKey key = new DeepSeekApiKey("sk-1234567890abcdef");
      assertThat(key.toString()).isEqualTo("DeepSeekApiKey[value=****]");
      assertThat(key.toString()).doesNotContain("sk-");
    }

    @Test
    @DisplayName("value() 返回原始密钥")
    void valueReturnsOriginal() {
      DeepSeekApiKey key = new DeepSeekApiKey("sk-secret");
      assertThat(key.value()).isEqualTo("sk-secret");
    }

    @Test
    @DisplayName("错误消息不包含密钥值")
    void errorMessageDoesNotLeakKey() {
      DeepSeekApiKey key = new DeepSeekApiKey("sk-leaked");
      String msg = "Invalid key: " + key;
      assertThat(msg).doesNotContain("sk-leaked");
      assertThat(msg).contains("****");
    }

    @Test
    @DisplayName("equals 和 hashCode 基于 value")
    void equalsAndHashCode() {
      DeepSeekApiKey key1 = new DeepSeekApiKey("sk-abc");
      DeepSeekApiKey key2 = new DeepSeekApiKey("sk-abc");
      DeepSeekApiKey key3 = new DeepSeekApiKey("sk-xyz");

      assertThat(key1).isEqualTo(key2);
      assertThat(key1).hasSameHashCodeAs(key2);
      assertThat(key1).isNotEqualTo(key3);
    }
  }

  // ============================================================
  // 默认值
  // ============================================================

  @Nested
  @DisplayName("默认值校验")
  class DefaultValues {

    @Test
    @DisplayName("空配置使用全部默认值")
    void emptyConfigUsesDefaults() {
      runnerWith()
          .run(
              ctx -> {
                QueryProperties props = ctx.getBean(QueryProperties.class);
                assertThat(props.getRetrieval().topN()).isEqualTo(8);
                assertThat(props.getContext().maxCharacters()).isEqualTo(12000);
                assertThat(props.getLlm().provider()).isEqualTo(Provider.STUB);
                assertThat(props.getLlm().requestTimeout()).isEqualTo(Duration.ofSeconds(120));
                assertThat(props.getLlm().stub()).isNotNull();
                assertThat(props.getLlm().stub().mode()).isEqualTo(StubMode.SUCCESS);
                // DeepSeek config absent because provider is STUB
                assertThat(props.getLlm().deepseek()).isNull();
              });
    }

    @Test
    @DisplayName("显式设置全部合法值")
    void explicitValidValues() {
      runnerWith(
              "crag.query.retrieval.top-n=15",
              "crag.query.context.max-characters=30000",
              "crag.query.llm.provider=deepseek",
              "crag.query.llm.request-timeout=60s",
              "crag.query.llm.deepseek.api-key=sk-valid",
              "crag.query.llm.deepseek.base-url=https://api.deepseek.com/anthropic",
              "crag.query.llm.deepseek.model=deepseek-v4-flash",
              "crag.query.llm.deepseek.temperature=0.5",
              "crag.query.llm.deepseek.max-output-tokens=8192")
          .run(
              ctx -> {
                QueryProperties props = ctx.getBean(QueryProperties.class);
                assertThat(props.getRetrieval().topN()).isEqualTo(15);
                assertThat(props.getContext().maxCharacters()).isEqualTo(30000);
                assertThat(props.getLlm().provider()).isEqualTo(Provider.DEEPSEEK);
                assertThat(props.getLlm().requestTimeout()).isEqualTo(Duration.ofSeconds(60));
                assertThat(props.getLlm().deepseek()).isNotNull();
                assertThat(props.getLlm().deepseek().apiKey().value()).isEqualTo("sk-valid");
                assertThat(props.getLlm().deepseek().baseUrl())
                    .isEqualTo("https://api.deepseek.com/anthropic");
                assertThat(props.getLlm().deepseek().model()).isEqualTo("deepseek-v4-flash");
                assertThat(props.getLlm().deepseek().temperature()).isEqualTo(0.5);
                assertThat(props.getLlm().deepseek().maxOutputTokens()).isEqualTo(8192);
              });
    }
  }

  // ============================================================
  // topN / maxCharacters 边界
  // ============================================================

  @Nested
  @DisplayName("范围校验 — retrieval & context")
  class RangeValidation {

    @Test
    @DisplayName("topN 边界值：1 和 50 合法")
    void topNBoundaries() {
      runnerWith("crag.query.retrieval.top-n=1").run(ctx -> assertThat(ctx).hasNotFailed());
      runnerWith("crag.query.retrieval.top-n=50").run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    @DisplayName("topN 超出范围 → 启动失败")
    void topNOutOfRange() {
      runnerWith("crag.query.retrieval.top-n=0")
          .run(
              ctx -> {
                assertThat(ctx).hasFailed();
                assertThat(ctx.getStartupFailure()).isNotNull();
              });
      runnerWith("crag.query.retrieval.top-n=51")
          .run(
              ctx -> {
                assertThat(ctx).hasFailed();
                assertThat(ctx.getStartupFailure()).isNotNull();
              });
    }

    @Test
    @DisplayName("maxCharacters 边界值：256 和 100000 合法")
    void maxCharactersBoundaries() {
      runnerWith("crag.query.context.max-characters=256")
          .run(ctx -> assertThat(ctx).hasNotFailed());
      runnerWith("crag.query.context.max-characters=100000")
          .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    @DisplayName("maxCharacters 超出范围 → 启动失败")
    void maxCharactersOutOfRange() {
      runnerWith("crag.query.context.max-characters=255")
          .run(
              ctx -> {
                assertThat(ctx).hasFailed();
                assertThat(ctx.getStartupFailure()).isNotNull();
              });
      runnerWith("crag.query.context.max-characters=100001")
          .run(
              ctx -> {
                assertThat(ctx).hasFailed();
                assertThat(ctx.getStartupFailure()).isNotNull();
              });
    }
  }

  // ============================================================
  // Stub 模式：不校验 DeepSeek 配置
  // ============================================================

  @Nested
  @DisplayName("Stub 模式宽松校验")
  class StubModeValidation {

    @Test
    @DisplayName("Stub 模式下缺少 DeepSeek 配置 → 启动正常")
    void stubModeIgnoresMissingDeepSeek() {
      runnerWith(
              "crag.query.llm.provider=stub",
              "crag.query.llm.request-timeout=30s",
              "crag.query.llm.stub.mode=failure")
          .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    @DisplayName("Stub 模式下提供 DeepSeek 配置但不完整 → 不会 NPE")
    void stubModeWithPartialDeepSeekDoesNotFail() {
      runnerWith(
              "crag.query.llm.provider=stub",
              "crag.query.llm.deepseek.api-key=some-key",
              "crag.query.llm.deepseek.temperature=-5")
          .run(ctx -> assertThat(ctx).hasNotFailed());
    }
  }

  // ============================================================
  // DeepSeek 模式：严格校验
  // ============================================================

  @Nested
  @DisplayName("DeepSeek 模式严格校验")
  class DeepSeekModeValidation {

    @Test
    @DisplayName("缺失 DeepSeek 配置 → 启动失败")
    void missingDeepSeekConfig() {
      runnerWith("crag.query.llm.provider=deepseek")
          .run(
              ctx -> {
                assertThat(ctx).hasFailed();
                assertThat(ctx.getStartupFailure()).isNotNull();
              });
    }

    @Test
    @DisplayName("空的 api-key → 启动失败")
    void emptyApiKey() {
      runnerWith(
              "crag.query.llm.provider=deepseek",
              "crag.query.llm.deepseek.api-key=",
              "crag.query.llm.deepseek.base-url=https://api.deepseek.com/anthropic",
              "crag.query.llm.deepseek.model=deepseek-v4-flash",
              "crag.query.llm.deepseek.max-output-tokens=4096")
          .run(
              ctx -> {
                assertThat(ctx).hasFailed();
                assertThat(ctx.getStartupFailure()).isNotNull();
              });
    }

    @Test
    @DisplayName("base-url 含 userinfo → 启动失败")
    void baseUrlWithUserinfo() {
      runnerWith(
              "crag.query.llm.provider=deepseek",
              "crag.query.llm.deepseek.api-key=sk-key",
              "crag.query.llm.deepseek.base-url=https://user@api.deepseek.com/anthropic",
              "crag.query.llm.deepseek.model=deepseek-v4-flash",
              "crag.query.llm.deepseek.max-output-tokens=4096")
          .run(
              ctx -> {
                assertThat(ctx).hasFailed();
                assertThat(ctx.getStartupFailure()).isNotNull();
              });
    }

    @Test
    @DisplayName("base-url 含 query → 启动失败")
    void baseUrlWithQuery() {
      runnerWith(
              "crag.query.llm.provider=deepseek",
              "crag.query.llm.deepseek.api-key=sk-key",
              "crag.query.llm.deepseek.base-url=https://api.deepseek.com/anthropic?version=2",
              "crag.query.llm.deepseek.model=deepseek-v4-flash",
              "crag.query.llm.deepseek.max-output-tokens=4096")
          .run(
              ctx -> {
                assertThat(ctx).hasFailed();
                assertThat(ctx.getStartupFailure()).isNotNull();
              });
    }

    @Test
    @DisplayName("base-url 含 fragment → 启动失败")
    void baseUrlWithFragment() {
      runnerWith(
              "crag.query.llm.provider=deepseek",
              "crag.query.llm.deepseek.api-key=sk-key",
              "crag.query.llm.deepseek.base-url=https://api.deepseek.com/anthropic#section",
              "crag.query.llm.deepseek.model=deepseek-v4-flash",
              "crag.query.llm.deepseek.max-output-tokens=4096")
          .run(
              ctx -> {
                assertThat(ctx).hasFailed();
                assertThat(ctx.getStartupFailure()).isNotNull();
              });
    }

    @Test
    @DisplayName("base-url 非 HTTPS → 启动失败")
    void baseUrlNonHttps() {
      runnerWith(
              "crag.query.llm.provider=deepseek",
              "crag.query.llm.deepseek.api-key=sk-key",
              "crag.query.llm.deepseek.base-url=http://api.deepseek.com/anthropic",
              "crag.query.llm.deepseek.model=deepseek-v4-flash",
              "crag.query.llm.deepseek.max-output-tokens=4096")
          .run(
              ctx -> {
                assertThat(ctx).hasFailed();
                assertThat(ctx.getStartupFailure()).isNotNull();
              });
    }

    @Test
    @DisplayName("base-url 非绝对 URI → 启动失败")
    void baseUrlRelative() {
      runnerWith(
              "crag.query.llm.provider=deepseek",
              "crag.query.llm.deepseek.api-key=sk-key",
              "crag.query.llm.deepseek.base-url=/anthropic",
              "crag.query.llm.deepseek.model=deepseek-v4-flash",
              "crag.query.llm.deepseek.max-output-tokens=4096")
          .run(
              ctx -> {
                assertThat(ctx).hasFailed();
                assertThat(ctx.getStartupFailure()).isNotNull();
              });
    }

    @Test
    @DisplayName("temperature 超出 0.0~1.0 → 启动失败")
    void temperatureOutOfRange() {
      runnerWith(
              "crag.query.llm.provider=deepseek",
              "crag.query.llm.deepseek.api-key=sk-key",
              "crag.query.llm.deepseek.base-url=https://api.deepseek.com/anthropic",
              "crag.query.llm.deepseek.model=deepseek-v4-flash",
              "crag.query.llm.deepseek.temperature=-0.1",
              "crag.query.llm.deepseek.max-output-tokens=4096")
          .run(
              ctx -> {
                assertThat(ctx).hasFailed();
                assertThat(ctx.getStartupFailure()).isNotNull();
              });
      runnerWith(
              "crag.query.llm.provider=deepseek",
              "crag.query.llm.deepseek.api-key=sk-key",
              "crag.query.llm.deepseek.base-url=https://api.deepseek.com/anthropic",
              "crag.query.llm.deepseek.model=deepseek-v4-flash",
              "crag.query.llm.deepseek.temperature=1.1",
              "crag.query.llm.deepseek.max-output-tokens=4096")
          .run(
              ctx -> {
                assertThat(ctx).hasFailed();
                assertThat(ctx.getStartupFailure()).isNotNull();
              });
    }

    @Test
    @DisplayName("maxOutputTokens 超出 256~16384 → 启动失败")
    void maxOutputTokensOutOfRange() {
      runnerWith(
              "crag.query.llm.provider=deepseek",
              "crag.query.llm.deepseek.api-key=sk-key",
              "crag.query.llm.deepseek.base-url=https://api.deepseek.com/anthropic",
              "crag.query.llm.deepseek.model=deepseek-v4-flash",
              "crag.query.llm.deepseek.max-output-tokens=255")
          .run(
              ctx -> {
                assertThat(ctx).hasFailed();
                assertThat(ctx.getStartupFailure()).isNotNull();
              });
      runnerWith(
              "crag.query.llm.provider=deepseek",
              "crag.query.llm.deepseek.api-key=sk-key",
              "crag.query.llm.deepseek.base-url=https://api.deepseek.com/anthropic",
              "crag.query.llm.deepseek.model=deepseek-v4-flash",
              "crag.query.llm.deepseek.max-output-tokens=16385")
          .run(
              ctx -> {
                assertThat(ctx).hasFailed();
                assertThat(ctx.getStartupFailure()).isNotNull();
              });
    }

    @Test
    @DisplayName("错误消息不包含 API 密钥")
    void errorMessageDoesNotContainApiKey() {
      ApplicationContextRunner runner =
          new ApplicationContextRunner()
              .withUserConfiguration(QueryLlmConfiguration.class)
              .withPropertyValues(
                  "crag.query.llm.provider=deepseek",
                  "crag.query.llm.deepseek.api-key=sk-super-secret-key-12345",
                  "crag.query.llm.deepseek.base-url=https://api.deepseek.com/anthropic",
                  "crag.query.llm.deepseek.model=deepseek-v4-flash",
                  // deliberately bad value to trigger failure
                  "crag.query.llm.deepseek.max-output-tokens=0");

      runner.run(
          ctx -> {
            Throwable failure = ctx.getStartupFailure();
            if (failure != null) {
              String message = failure.getMessage();
              // If there's a failure, it must not leak the key
              assertThat(message).doesNotContain("sk-super-secret-key-12345");
            }
          });
    }
  }

  // ============================================================
  // 枚举大小写不敏感
  // ============================================================

  @Nested
  @DisplayName("枚举大小写不敏感")
  class EnumCaseInsensitive {

    @Test
    @DisplayName("Provider 枚举大小写不敏感")
    void providerCaseInsensitive() {
      runnerWith("crag.query.llm.provider=STUB")
          .run(
              ctx ->
                  assertThat(ctx.getBean(QueryProperties.class).getLlm().provider())
                      .isEqualTo(Provider.STUB));
      runnerWith("crag.query.llm.provider=stub")
          .run(
              ctx ->
                  assertThat(ctx.getBean(QueryProperties.class).getLlm().provider())
                      .isEqualTo(Provider.STUB));
      runnerWith("crag.query.llm.provider=StUb")
          .run(
              ctx ->
                  assertThat(ctx.getBean(QueryProperties.class).getLlm().provider())
                      .isEqualTo(Provider.STUB));
      runnerWith(
              "crag.query.llm.provider=DEEPSEEK",
              "crag.query.llm.deepseek.api-key=sk-key",
              "crag.query.llm.deepseek.base-url=https://api.deepseek.com/anthropic",
              "crag.query.llm.deepseek.model=deepseek-v4-flash",
              "crag.query.llm.deepseek.max-output-tokens=4096")
          .run(
              ctx ->
                  assertThat(ctx.getBean(QueryProperties.class).getLlm().provider())
                      .isEqualTo(Provider.DEEPSEEK));
    }

    @Test
    @DisplayName("StubMode 枚举大小写不敏感")
    void stubModeCaseInsensitive() {
      runnerWith("crag.query.llm.stub.mode=SUCCESS")
          .run(
              ctx ->
                  assertThat(ctx.getBean(QueryProperties.class).getLlm().stub().mode())
                      .isEqualTo(StubMode.SUCCESS));
      runnerWith("crag.query.llm.stub.mode=success")
          .run(
              ctx ->
                  assertThat(ctx.getBean(QueryProperties.class).getLlm().stub().mode())
                      .isEqualTo(StubMode.SUCCESS));
      runnerWith("crag.query.llm.stub.mode=Failure")
          .run(
              ctx ->
                  assertThat(ctx.getBean(QueryProperties.class).getLlm().stub().mode())
                      .isEqualTo(StubMode.FAILURE));
    }

    @Test
    @DisplayName("无效枚举值 → 启动失败")
    void invalidEnumValue() {
      runnerWith("crag.query.llm.provider=unknown")
          .run(
              ctx -> {
                assertThat(ctx).hasFailed();
                assertThat(ctx.getStartupFailure()).isNotNull();
              });
    }
  }

  // ============================================================
  // env var 覆盖
  // ============================================================

  @Nested
  @DisplayName("环境变量覆盖")
  class EnvOverride {

    @Test
    @DisplayName("通过配置前缀覆盖 requestTimeout")
    void overrideRequestTimeout() {
      runnerWith("crag.query.llm.request-timeout=30s")
          .run(
              ctx ->
                  assertThat(ctx.getBean(QueryProperties.class).getLlm().requestTimeout())
                      .isEqualTo(Duration.ofSeconds(30)));
    }

    @Test
    @DisplayName("通过配置前缀覆盖 provider")
    void overrideProvider() {
      runnerWith(
              "crag.query.llm.provider=deepseek",
              "crag.query.llm.deepseek.api-key=sk-override",
              "crag.query.llm.deepseek.base-url=https://api.deepseek.com/anthropic",
              "crag.query.llm.deepseek.model=deepseek-v4-flash",
              "crag.query.llm.deepseek.max-output-tokens=4096")
          .run(
              ctx ->
                  assertThat(ctx.getBean(QueryProperties.class).getLlm().provider())
                      .isEqualTo(Provider.DEEPSEEK));
    }
  }

  // ============================================================
  // 直接 Jakarta Validator 验证
  // ============================================================

  @Nested
  @DisplayName("Validator 直接校验")
  class DirectValidator {

    @Test
    @SuppressWarnings("deprecation")
    @DisplayName("topN = 0 违反 @Min(1)")
    void topNZeroViolatesMin() {
      var factory = jakarta.validation.Validation.buildDefaultValidatorFactory();
      Validator validator = factory.getValidator();

      QueryProperties props = new QueryProperties();
      props.setRetrieval(new QueryProperties.Retrieval(0));
      props.setContext(new QueryProperties.Context(12000));
      props.setLlm(
          new QueryProperties.Llm(
              Provider.STUB,
              Duration.ofSeconds(120),
              null,
              new QueryProperties.Stub(StubMode.SUCCESS)));

      Set<ConstraintViolation<QueryProperties>> violations = validator.validate(props);
      assertThat(violations).isNotEmpty();
      assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().contains("topN"));
    }

    @Test
    @SuppressWarnings("deprecation")
    @DisplayName("maxCharacters = 255 违反 @Min(256)")
    void maxCharactersTooLow() {
      var factory = jakarta.validation.Validation.buildDefaultValidatorFactory();
      Validator validator = factory.getValidator();

      QueryProperties props = new QueryProperties();
      props.setRetrieval(new QueryProperties.Retrieval(8));
      props.setContext(new QueryProperties.Context(255));
      props.setLlm(
          new QueryProperties.Llm(
              Provider.STUB,
              Duration.ofSeconds(120),
              null,
              new QueryProperties.Stub(StubMode.SUCCESS)));

      Set<ConstraintViolation<QueryProperties>> violations = validator.validate(props);
      assertThat(violations).isNotEmpty();
      assertThat(violations)
          .anyMatch(v -> v.getPropertyPath().toString().contains("maxCharacters"));
    }
  }
}
