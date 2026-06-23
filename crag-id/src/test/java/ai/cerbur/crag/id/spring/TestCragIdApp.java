package ai.cerbur.crag.id.spring;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Minimal Spring Boot test configuration for {@link CragIdConfigurationComponentTest}.
 *
 * <p>Provides enough beans to satisfy the Crag ID module without a real Redis connection.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@EnableConfigurationProperties(CragIdProperties.class)
class TestCragIdApp {

  /** Satisfy the {@code @ConditionalOnBean(TaskScheduler.class)} guard. */
  @Bean
  TaskScheduler taskScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("test-scheduler-");
    scheduler.initialize();
    return scheduler;
  }
}
