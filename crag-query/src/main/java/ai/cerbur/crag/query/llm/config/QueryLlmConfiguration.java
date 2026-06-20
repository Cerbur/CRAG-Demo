package ai.cerbur.crag.query.llm.config;

import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;

/**
 * Query LLM 配置引导 —— 注册 {@link QueryProperties} 配置属性绑定及必要的类型转换器.
 *
 * <p>当前只注册 {@link DeepSeekApiKey} 转换器和 {@code @EnableConfigurationProperties} 扫描； Bean 定义由后续 Task
 * 7.3 补充.
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
}
