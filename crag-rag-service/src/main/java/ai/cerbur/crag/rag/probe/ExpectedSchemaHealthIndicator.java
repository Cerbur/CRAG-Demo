package ai.cerbur.crag.rag.probe;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("expectedSchema")
public class ExpectedSchemaHealthIndicator implements HealthIndicator {

  @Autowired private DataSource dataSource;

  @Override
  public Health health() {
    try (Connection conn = dataSource.getConnection();
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT current_schema()")) {
      if (rs.next()) {
        String schema = rs.getString(1);
        if ("rag".equals(schema)) {
          return Health.up().withDetail("schema", schema).build();
        }
        return Health.down().withDetail("schema", schema).withDetail("expected", "rag").build();
      }
      return Health.down().withDetail("error", "no result").build();
    } catch (Exception e) {
      return Health.down().withException(e).build();
    }
  }
}
