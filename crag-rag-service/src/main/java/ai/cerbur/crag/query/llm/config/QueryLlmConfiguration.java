package ai.cerbur.crag.query.llm.config;

import ai.cerbur.crag.query.llm.adapter.deepseek.DeepSeekAnthropicLlmAdapter;
import ai.cerbur.crag.query.llm.adapter.stub.StubLlmAdapter;
import ai.cerbur.crag.query.llm.contract.LlmClient;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;

/**
 * Query LLM 配置引导 —— 注册 {@link QueryProperties} 配置属性绑定、类型转换器及 LLM 适配器 Bean.
 *
 * <p>当前支持：
 *
 * <ul>
 *   <li>{@code crag.query.llm.provider=stub} → {@link StubLlmAdapter}
 *   <li>{@code crag.query.llm.provider=deepseek} → {@link DeepSeekAnthropicLlmAdapter}
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(QueryProperties.class)
public class QueryLlmConfiguration {

  /**
   * String → {@link DeepSeekApiKey} 转换器，供 {@code @ConfigurationProperties} 绑定 {@code
   * deepseek.api-key} 时使用.
   */
  @Bean
  @ConfigurationPropertiesBinding
  Converter<String, DeepSeekApiKey> deepSeekApiKeyConverter() {
    return DeepSeekApiKey::new;
  }

  /**
   * Stub LLM 适配器 —— 当 {@code crag.query.llm.provider=stub}（默认值）时装配.
   *
   * <p>Stub 模式下不校验 DeepSeek 配置，且不调用任何网络.
   */
  @Bean
  @ConditionalOnProperty(
      prefix = "crag.query.llm",
      name = "provider",
      havingValue = "stub",
      matchIfMissing = true)
  LlmClient stubLlmAdapter(QueryProperties properties) {
    return new StubLlmAdapter(properties);
  }

  /**
   * DeepSeek Anthropic 适配器 —— 当 {@code crag.query.llm.provider=deepseek} 时装配.
   *
   * <p>通过 Spring AI 2.0.0 AnthropicChatModel 调用 DeepSeek V4 Flash 的 Anthropic 兼容 API.
   */
  @Bean
  @ConditionalOnProperty(prefix = "crag.query.llm", name = "provider", havingValue = "deepseek")
  LlmClient deepSeekLlmAdapter(AnthropicChatModel chatModel, QueryProperties properties) {
    return new DeepSeekAnthropicLlmAdapter(chatModel, properties);
  }

  /**
   * Spring AI AnthropicChatModel —— 当 {@code crag.query.llm.provider=deepseek} 时装配.
   *
   * <p>手动构建 AnthropicChatModel（非启用器），配置传输参数（apiKey、baseUrl、timeout、maxRetries=0）和默认
   * 请求参数（model、temperature、maxTokens）.
   */
  @Bean
  @ConditionalOnProperty(prefix = "crag.query.llm", name = "provider", havingValue = "deepseek")
  AnthropicChatModel anthropicChatModel(QueryProperties properties) {
    var deepseek = properties.getLlm().deepseek();
    var options =
        AnthropicChatOptions.builder()
            .apiKey(deepseek.apiKey().value())
            .baseUrl(deepseek.baseUrl())
            .model(deepseek.model())
            .temperature(deepseek.temperature())
            .maxTokens(deepseek.maxOutputTokens())
            .timeout(properties.getLlm().requestTimeout())
            .maxRetries(0)
            .build();

    return AnthropicChatModel.builder().options(options).build();
  }
}
