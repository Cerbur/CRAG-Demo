package ai.cerbur.crag.event.spring;

import static org.mockito.Mockito.mock;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

/** Minimal beans for {@code crag-event} auto-configuration component tests. */
@Configuration
public class EventAutoConfigurationTestBeans {

  @Bean
  DataSource dataSource() {
    return new EmbeddedDatabaseBuilder()
        .setType(EmbeddedDatabaseType.H2)
        .setName("event-autocfg")
        .build();
  }

  @Bean
  JdbcTemplate jdbcTemplate(DataSource dataSource) {
    return new JdbcTemplate(dataSource);
  }

  @Bean
  StringRedisTemplate stringRedisTemplate() {
    return mock(StringRedisTemplate.class);
  }

  @Bean
  MeterRegistry meterRegistry() {
    return new SimpleMeterRegistry();
  }
}
