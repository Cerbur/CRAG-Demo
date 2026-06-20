package ai.cerbur.crag.app;

import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * CRAG-Demo 启动类 —— 基于 RAG 的问答机器人后端服务入口.
 *
 * <p>作为 multi-module 架构中唯一的 Spring Boot 启动模块（crag-app）， 负责装配
 * crag-api、crag-ingestion、crag-retrieval、crag-query、crag-storage 等模块的 Bean. 当前运行时仍是一个进程、一个端口、一个
 * Docker service.
 *
 * @since 2026-06-10
 */
@SpringBootApplication
@ComponentScan("ai.cerbur.crag")
@EnableJpaRepositories("ai.cerbur.crag.storage.repository")
@EnableScheduling
public class CragDemoApplication {

  /**
   * 注册实体扫描包 —— 替代 Boot 3 已移除的 @EntityScan.
   *
   * <p>crag-storage.entity 不在 @SpringBootApplication 所在包的子包中， 需要显式告知 EntityManagerFactory 扫描该包.
   */
  @Bean
  static BeanDefinitionRegistryPostProcessor entityPackageRegistrar() {
    return registry ->
        AutoConfigurationPackages.register(registry, "ai.cerbur.crag.storage.entity");
  }

  public static void main(String[] args) {
    SpringApplication.run(CragDemoApplication.class, args);
  }
}
