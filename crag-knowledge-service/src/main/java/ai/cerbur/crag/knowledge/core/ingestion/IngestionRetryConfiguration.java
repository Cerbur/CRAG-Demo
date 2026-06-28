package ai.cerbur.crag.knowledge.core.ingestion;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Ingestion retry/reconcile bean 装配（plan_21/21.5）。
 *
 * <p>{@link RetryPolicy} 是纯函数策略类，按设计不携带 Spring 注解；本配置以默认参数注册为单例 bean，供 {@link
 * IngestionRetryService} 与 {@code IngestionReconcileService} 注入。测试可覆盖本 bean 注入更短退避。
 */
@Configuration
public class IngestionRetryConfiguration {

  @Bean
  public RetryPolicy ingestionRetryPolicy() {
    return new RetryPolicy();
  }
}
