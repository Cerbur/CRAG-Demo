package ai.cerbur.crag.knowledge;

import ai.cerbur.crag.grpc.runtime.config.GrpcClientConfiguration;
import ai.cerbur.crag.grpc.runtime.config.GrpcServerConfiguration;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScanPackages;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Knowledge 服务组合根，直接位于 {@code ai.cerbur.crag.knowledge} 根包。
 *
 * <p>组件扫描限定在 Knowledge 包，避免误装配其他服务的 Bean；JPA Repository 扫描 {@code dao.repository}，实体扫描 {@code
 * dao.entity}；gRPC Server 生命周期由 {@link GrpcServerConfiguration} 提供，所有 Spring 管理的 {@code
 * BindableService} 自动注册。Plan 21.5 起追加 {@link GrpcClientConfiguration}，为 Reconciler gRPC 客户端（调用 RAG
 * Status RPC）装配 {@code GrpcChannelFactory}。
 */
@SpringBootApplication
@ComponentScan("ai.cerbur.crag.knowledge")
@EnableJpaRepositories("ai.cerbur.crag.knowledge.dao.repository")
@Import({GrpcServerConfiguration.class, GrpcClientConfiguration.class})
public class KnowledgeServiceApplication {

  @Bean
  static BeanDefinitionRegistryPostProcessor entityPackageRegistrar() {
    return registry -> EntityScanPackages.register(registry, "ai.cerbur.crag.knowledge.dao.entity");
  }

  public static void main(String[] args) {
    SpringApplication.run(KnowledgeServiceApplication.class, args);
  }
}
