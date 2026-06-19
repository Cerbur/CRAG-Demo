package ai.cerbur.crag.api;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

/**
 * 测试专用最小 Spring Boot 配置 —— 为 crag-api 库模块的 Spring Boot 测试提供配置.
 *
 * <p>排除数据库、安全与 Spring AI 自动配置；不启用 @ComponentScan。仅存在于 test source set。
 */
@SpringBootConfiguration
@EnableAutoConfiguration(
    exclude = {
      DataSourceAutoConfiguration.class,
      DataSourceTransactionManagerAutoConfiguration.class,
      HibernateJpaAutoConfiguration.class,
      SecurityAutoConfiguration.class,
      UserDetailsServiceAutoConfiguration.class
    })
public class TestSpringBootConfiguration {}
