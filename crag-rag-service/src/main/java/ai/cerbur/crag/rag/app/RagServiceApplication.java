package ai.cerbur.crag.rag.app;

import ai.cerbur.crag.event.spring.EventAutoConfiguration;
import ai.cerbur.crag.grpc.runtime.config.GrpcClientConfiguration;
import ai.cerbur.crag.grpc.runtime.config.GrpcServerConfiguration;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScanPackages;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 排除 {@link EventAutoConfiguration} 等库模块的自动装配类被组件扫描过早加载：{@code @ComponentScan("ai.cerbur.crag")} 覆盖
 * crag-event 包，会把带 {@code @Configuration} 元注解的 {@code EventAutoConfiguration} 当作普通配置在自动装配阶段
 * 之前处理，导致其 {@code @ConditionalOnBean(StringRedisTemplate/JdbcTemplate)} 在依赖 bean 注册前求值而失败。 排除后
 * EventAutoConfiguration 仅由自动装配发现（带正确的 afterName 排序）.
 */
@SpringBootApplication
@ComponentScan(
    basePackages = "ai.cerbur.crag",
    excludeFilters =
        @ComponentScan.Filter(
            type = FilterType.ASSIGNABLE_TYPE,
            classes = EventAutoConfiguration.class))
@EnableJpaRepositories("ai.cerbur.crag.storage.repository")
@EnableScheduling
@Import({GrpcServerConfiguration.class, GrpcClientConfiguration.class})
public class RagServiceApplication {

  @Bean
  static BeanDefinitionRegistryPostProcessor entityPackageRegistrar() {
    return registry -> EntityScanPackages.register(registry, "ai.cerbur.crag.storage.entity");
  }

  public static void main(String[] args) {
    SpringApplication.run(RagServiceApplication.class, args);
  }
}
