package ai.cerbur.crag.id.spring;

import ai.cerbur.crag.id.api.IdEntityType;
import ai.cerbur.crag.id.redis.RedisWorkerLease;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * Actuator {@link HealthIndicator} that reports the readiness of the Crag ID generator.
 *
 * <p>Maps to {@code DOWN} when any required entity type lacks an active worker lease or the clock
 * is in a rollback state. Readiness details include per-entity lease status and worker assignment.
 */
public class CragIdHealthIndicator implements HealthIndicator {

  private final LeaseAccessor leaseAccessor;
  private final Set<IdEntityType> requiredEntities;

  @FunctionalInterface
  public interface LeaseAccessor {
    /** Return the current lease for the given entity type, or {@code null} if no lease is held. */
    RedisWorkerLease getLease(IdEntityType entityType);
  }

  public CragIdHealthIndicator(LeaseAccessor leaseAccessor, Set<IdEntityType> requiredEntities) {
    this.leaseAccessor = leaseAccessor;
    this.requiredEntities = requiredEntities;
  }

  @Override
  public Health health() {
    if (requiredEntities.isEmpty()) {
      return Health.up().withDetail("cragId", "no required entities configured").build();
    }

    Map<String, Object> details = new LinkedHashMap<>();
    boolean allActive = true;

    for (IdEntityType entityType : requiredEntities) {
      RedisWorkerLease lease = leaseAccessor.getLease(entityType);
      if (lease == null) {
        details.put(entityType.name(), "no_lease");
        allActive = false;
      } else if (lease.isActive()) {
        details.put(
            entityType.name(),
            Map.of("status", "active", "worker", String.valueOf(lease.workerId())));
      } else {
        details.put(
            entityType.name(),
            Map.of("status", "lost", "worker", String.valueOf(lease.workerId())));
        allActive = false;
      }
    }

    Health.Builder builder = allActive ? Health.up() : Health.down();
    return builder.withDetail("cragId", details).build();
  }
}
