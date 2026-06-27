package ai.cerbur.crag.access;

import ai.cerbur.crag.grpc.runtime.config.GrpcServerConfiguration;
import ai.cerbur.crag.id.spring.CragIdConfiguration;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScanPackages;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Access 服务组合根，直接位于 {@code ai.cerbur.crag.access} 根包（plan_20 不保留 {@code app} 包）。
 *
 * <p>组件扫描限定在 Access 包；JPA Repository 扫描 {@code dao.repository}，实体扫描 {@code dao.entity}；gRPC Server
 * 生命周期由 {@link GrpcServerConfiguration} 提供，所有 Spring 管理的 {@code BindableService} 自动注册。{@link
 * CragIdConfiguration} 按 {@code crag.id.service-domain} 门控装载 Snowflake 发号；{@code crag-event}
 * 经自动配置装配。
 */
@SpringBootApplication
@ComponentScan("ai.cerbur.crag.access")
@EnableJpaRepositories("ai.cerbur.crag.access.dao.repository")
@Import({GrpcServerConfiguration.class, CragIdConfiguration.class})
public class AccessServiceApplication {

  @Bean
  static BeanDefinitionRegistryPostProcessor entityPackageRegistrar() {
    return registry -> EntityScanPackages.register(registry, "ai.cerbur.crag.access.dao.entity");
  }

  public static void main(String[] args) {
    SpringApplication.run(AccessServiceApplication.class, args);
  }
}
