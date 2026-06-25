package ai.cerbur.crag.event.jdbc;

import javax.sql.DataSource;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.DataSourceInitializer;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/**
 * Minimal JDBC test context for crag-event component tests.
 *
 * <p>Drives the {@code @JdbcTest} slice (DataSource, JdbcTemplate, transaction manager) and loads
 * {@code schema.sql} once at startup via a {@link DataSourceInitializer}, so tables live outside
 * the per-test transaction rollback. This is a test fixture only; production Knowledge smoke tables
 * are created by {@code crag-knowledge-service}.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
public class EventJdbcTestConfiguration {

  @Bean
  DataSourceInitializer eventSchemaInitializer(DataSource dataSource) {
    DataSourceInitializer initializer = new DataSourceInitializer();
    initializer.setDataSource(dataSource);
    ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
    populator.addScript(new ClassPathResource("schema.sql"));
    populator.setIgnoreFailedDrops(true);
    populator.setContinueOnError(false);
    initializer.setDatabasePopulator(populator);
    return initializer;
  }
}
