package ai.cerbur.crag.open.app;

import ai.cerbur.crag.grpc.runtime.config.GrpcClientConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;

/**
 * Open API 组合根（plan_21/21.10）。
 *
 * <p>Open 无数据库：显式排除 {@code DataSource}/{@code JPA}/{@code DataSourceTransactionManager} 自动配置，确保
 * crag-event 传递引入的 {@code spring-boot-starter-jdbc} 不会在 Open 进程内创建 DataSource 或启动
 * publisher/consumer 的 DB 依赖 Bean。Open 只使用 {@code EphemeralRedisStreamConsumer}（无 JDBC
 * processed_event）。
 *
 * <p>使用 {@code excludeName}（FQN 字符串）而非 class 引用，避免 Open 对 spring-boot-jdbc autoconfigure 包的
 * 编译期依赖；Spring Boot 4.x 的 autoconfigure 包路径与 EventAutoConfiguration 的 {@code afterName} 一致。
 */
@SpringBootApplication(
    excludeName = {
      "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
      "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
      "org.springframework.boot.orm.jpa.autoconfigure.HibernateJpaAutoConfiguration"
    })
@ComponentScan("ai.cerbur.crag.open")
@Import(GrpcClientConfiguration.class)
public class OpenApiApplication {

  public static void main(String[] args) {
    SpringApplication.run(OpenApiApplication.class, args);
  }
}
