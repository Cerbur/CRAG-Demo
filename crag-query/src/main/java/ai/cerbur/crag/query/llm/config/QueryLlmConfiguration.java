package ai.cerbur.crag.query.llm.config;

import ai.cerbur.crag.query.llm.adapter.stub.StubLlmAdapter;
import ai.cerbur.crag.query.llm.contract.LlmClient;
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
 *   <li>DeepSeek 适配器由后续 Task 补充
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
}
