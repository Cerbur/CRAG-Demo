package ai.cerbur.crag.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
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
@EntityScan("ai.cerbur.crag.storage.entity")
@EnableScheduling
public class CragDemoApplication {

  public static void main(String[] args) {
    SpringApplication.run(CragDemoApplication.class, args);
  }
}
