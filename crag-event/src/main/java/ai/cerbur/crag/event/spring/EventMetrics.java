package ai.cerbur.crag.event.spring;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Binds outbox-state gauges to Micrometer, giving scraper-style visibility into how many events are
 * pending, waiting for retry or dead — without instrumenting every publish/consume call.
 */
public class EventMetrics implements MeterBinder {

  private final JdbcTemplate jdbcTemplate;

  public EventMetrics(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public void bindTo(MeterRegistry registry) {
    registry.gauge(
        "crag_event_outbox_pending", countByStatus("PENDING"), EventMetrics::doubleValue);
    registry.gauge(
        "crag_event_outbox_retry_wait", countByStatus("RETRY_WAIT"), EventMetrics::doubleValue);
    registry.gauge(
        "crag_event_outbox_publishing", countByStatus("PUBLISHING"), EventMetrics::doubleValue);
    registry.gauge("crag_event_outbox_dead", countByStatus("DEAD"), EventMetrics::doubleValue);
  }

  private static double doubleValue(Supplier<Number> supplier) {
    Number value = supplier.get();
    return value == null ? 0.0 : value.doubleValue();
  }

  private Supplier<Number> countByStatus(String status) {
    return () -> {
      try {
        Integer count =
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE status = ?", Integer.class, status);
        return count == null ? 0 : count;
      } catch (Exception e) {
        return 0;
      }
    };
  }
}
