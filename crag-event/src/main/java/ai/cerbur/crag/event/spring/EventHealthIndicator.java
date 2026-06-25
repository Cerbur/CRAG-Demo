package ai.cerbur.crag.event.spring;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Optional Actuator {@link HealthIndicator} for the event infrastructure.
 *
 * <p>Probes the outbox table reachability and a Redis PING, surfacing a clear detail for whichever
 * dependency is unavailable. Disabled by default; enable with {@code
 * crag.event.health.enabled=true}.
 */
public class EventHealthIndicator implements HealthIndicator {

  private final JdbcTemplate jdbcTemplate;
  private final StringRedisTemplate redis;

  public EventHealthIndicator(JdbcTemplate jdbcTemplate, StringRedisTemplate redis) {
    this.jdbcTemplate = jdbcTemplate;
    this.redis = redis;
  }

  @Override
  public Health health() {
    Map<String, Object> details = new LinkedHashMap<>();
    boolean up = true;

    try {
      Integer pending =
          jdbcTemplate.queryForObject("SELECT COUNT(*) FROM outbox_event", Integer.class);
      details.put("outbox", "reachable");
      details.put("outboxPending", pending == null ? 0 : pending);
    } catch (Exception e) {
      up = false;
      details.put("outbox", "unreachable: " + rootMessage(e));
    }

    try (var connection = redis.getConnectionFactory().getConnection()) {
      connection.ping();
      details.put("redis", "up");
    } catch (Exception e) {
      up = false;
      details.put("redis", "down: " + rootMessage(e));
    }

    return (up ? Health.up() : Health.down()).withDetail("cragEvent", details).build();
  }

  private static String rootMessage(Throwable e) {
    Throwable cause = e;
    while (cause.getCause() != null && cause.getCause() != cause) {
      cause = cause.getCause();
    }
    return cause.getClass().getSimpleName() + ": " + cause.getMessage();
  }
}
