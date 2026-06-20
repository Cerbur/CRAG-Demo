package ai.cerbur.crag.query.llm.config;

import jakarta.annotation.PostConstruct;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Query 模块配置属性 —— 绑定 {@code crag.query.*} 前缀.
 *
 * <p>支持环境变量覆盖（如 {@code CRAG_QUERY_LLM_PROVIDER}），启动时校验合法性。 分为 retrieval、context 与 llm 三大块，通过 {@link
 * Provider} 切换 LLM 提供商。
 *
 * <p>Stub 模式下不校验 DeepSeek 配置；DeepSeek 模式下校验密钥、端点 URL、模型名等全部必填字段。
 */
@ConfigurationProperties("crag.query")
@Validated
public class QueryProperties {

  @Valid @NotNull private Retrieval retrieval = new Retrieval(8);
  @Valid @NotNull private Context context = new Context(12000);

  @Valid @NotNull
  private Llm llm =
      new Llm(Provider.STUB, Duration.ofSeconds(120), null, new Stub(StubMode.SUCCESS));

  public QueryProperties() {}

  // ---- Lifecycle ----

  /** 应用默认值并执行跨字段校验. */
  @PostConstruct
  void init() {
    applyDefaults();
    if (llm.provider() == Provider.DEEPSEEK) {
      validateDeepSeekConfig();
    }
  }

  private void applyDefaults() {
    if (retrieval == null) {
      retrieval = new Retrieval(8);
    }
    if (context == null) {
      context = new Context(12000);
    }
    if (llm == null) {
      llm = new Llm(Provider.STUB, Duration.ofSeconds(120), null, new Stub(StubMode.SUCCESS));
      return;
    }

    Provider provider = llm.provider();
    Duration timeout = llm.requestTimeout();
    DeepSeek ds = llm.deepseek();
    Stub stub = llm.stub();

    // provider
    if (provider == null) {
      provider = Provider.STUB;
    }

    // request-timeout
    if (timeout == null) {
      timeout = Duration.ofSeconds(120);
    }

    // stub defaults
    if (stub == null) {
      if (provider == Provider.STUB) {
        stub = new Stub(StubMode.SUCCESS);
      }
    } else if (stub.mode() == null) {
      stub = new Stub(StubMode.SUCCESS);
    }

    // deepseek defaults (only applied when record exists, no defaults when absent)
    if (ds != null) {
      String baseUrl = ds.baseUrl();
      String model = ds.model();
      double temperature = ds.temperature();
      int maxTokens = ds.maxOutputTokens();

      if (baseUrl == null) {
        baseUrl = "https://api.deepseek.com/anthropic";
      }
      if (model == null) {
        model = "deepseek-v4-flash";
      }
      // temperature 0.0 is both default and valid sentinel
      // maxOutputTokens: 0 sentinel means "not set" -> 4096
      if (maxTokens == 0) {
        maxTokens = 4096;
      }

      ds = new DeepSeek(ds.apiKey(), baseUrl, model, temperature, maxTokens);
    }

    this.llm = new Llm(provider, timeout, ds, stub);
  }

  // ---- DeepSeek 条件校验 ----

  private void validateDeepSeekConfig() {
    DeepSeek ds = llm.deepseek();
    if (ds == null) {
      throw new IllegalArgumentException(
          "crag.query.llm.deepseek configuration is required when provider is DEEPSEEK");
    }
    // api-key — 非空白
    if (ds.apiKey() == null || ds.apiKey().value().isBlank()) {
      throw new IllegalArgumentException("DEEPSEEK_API_KEY must be set when provider is DEEPSEEK");
    }
    // base-url — 绝对 HTTPS，允许路径，禁止 userinfo/query/fragment
    String baseUrl = ds.baseUrl();
    if (baseUrl == null || baseUrl.isBlank()) {
      throw new IllegalArgumentException(
          "crag.query.llm.deepseek.base-url must be set when provider is DEEPSEEK");
    }
    try {
      URI uri = new URI(baseUrl);
      if (!"https".equals(uri.getScheme())) {
        throw new IllegalArgumentException(
            "crag.query.llm.deepseek.base-url must use HTTPS scheme");
      }
      if (!uri.isAbsolute()) {
        throw new IllegalArgumentException(
            "crag.query.llm.deepseek.base-url must be an absolute URI");
      }
      if (uri.getUserInfo() != null) {
        throw new IllegalArgumentException(
            "crag.query.llm.deepseek.base-url must not contain user information");
      }
      if (uri.getQuery() != null) {
        throw new IllegalArgumentException(
            "crag.query.llm.deepseek.base-url must not contain query parameters");
      }
      if (uri.getFragment() != null) {
        throw new IllegalArgumentException(
            "crag.query.llm.deepseek.base-url must not contain fragment");
      }
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException(
          "crag.query.llm.deepseek.base-url is not a valid URI: " + e.getMessage(), e);
    }
    // model — 非空白
    if (ds.model() == null || ds.model().isBlank()) {
      throw new IllegalArgumentException(
          "crag.query.llm.deepseek.model must be set when provider is DEEPSEEK");
    }
    // temperature — 0.0..1.0
    double temperature = ds.temperature();
    if (temperature < 0.0 || temperature > 1.0) {
      throw new IllegalArgumentException(
          "crag.query.llm.deepseek.temperature must be between 0.0 and 1.0, got " + temperature);
    }
    // max-output-tokens — 256..16384
    int maxTokens = ds.maxOutputTokens();
    if (maxTokens < 256 || maxTokens > 16384) {
      throw new IllegalArgumentException(
          "crag.query.llm.deepseek.max-output-tokens must be between 256 and 16384, got "
              + maxTokens);
    }
    // request-timeout — 正 Duration
    Duration timeout = llm.requestTimeout();
    if (timeout == null || timeout.isNegative() || timeout.isZero()) {
      throw new IllegalArgumentException(
          "crag.query.llm.request-timeout must be a positive duration");
    }
  }

  // ---- Getters / Setters ----

  public Retrieval getRetrieval() {
    return retrieval;
  }

  public void setRetrieval(Retrieval retrieval) {
    this.retrieval = retrieval;
  }

  public Context getContext() {
    return context;
  }

  public void setContext(Context context) {
    this.context = context;
  }

  public Llm getLlm() {
    return llm;
  }

  public void setLlm(Llm llm) {
    this.llm = llm;
  }

  // ---- Nested records ----

  /** 召回参数. */
  public record Retrieval(@Min(1) @Max(50) int topN) {}

  /** 上下文预算. */
  public record Context(@Min(256) @Max(100000) int maxCharacters) {}

  /**
   * LLM 配置根.
   *
   * <p>{@code provider} 和 {@code stub} 可能因部分绑定为 null，在 {@link #init()} 中应用默认值。 校验在 {@code init()}
   * 中统一处理，此处不标记 {@code @NotNull}。
   */
  public record Llm(Provider provider, Duration requestTimeout, DeepSeek deepseek, Stub stub) {}

  /** DeepSeek 提供商配置（校验由 {@link #validateDeepSeekConfig()} 在 DeepSeek 模式下完成）. */
  public record DeepSeek(
      DeepSeekApiKey apiKey,
      String baseUrl,
      String model,
      double temperature,
      int maxOutputTokens) {}

  /** Stub / Test-double 配置（默认值在 {@link #init()} 中应用）. */
  public record Stub(StubMode mode) {}

  // ---- Enums ----

  /** LLM 提供商枚举. */
  public enum Provider {
    STUB,
    DEEPSEEK
  }

  /** Stub 模式枚举. */
  public enum StubMode {
    SUCCESS,
    FAILURE
  }
}
