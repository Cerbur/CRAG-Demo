package ai.cerbur.crag.id.spring;

import ai.cerbur.crag.id.api.CragIdGenerator;
import ai.cerbur.crag.id.api.CragIdParser;
import ai.cerbur.crag.id.api.DefaultCragIdParser;
import ai.cerbur.crag.id.api.IdEntityType;
import ai.cerbur.crag.id.internal.MonotonicClock;
import ai.cerbur.crag.id.internal.RedisBackedCragIdGenerator;
import ai.cerbur.crag.id.internal.SnowflakeLayout;
import ai.cerbur.crag.id.internal.SystemMonotonicClock;
import ai.cerbur.crag.id.redis.RedisWorkerLease;
import ai.cerbur.crag.id.redis.RedisWorkerLeaseRepository;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Spring configuration for the Crag ID module.
 *
 * <p>Registers the {@link CragIdGenerator}, {@link CragIdParser}, lease pool, renewal scheduler,
 * and Actuator {@link HealthIndicator}. Bean creation is gated on {@code crag.id.service-domain} so
 * that services that don't need Snowflake IDs are not forced to provide Redis.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CragIdProperties.class)
@ConditionalOnProperty(prefix = "crag.id", name = "service-domain")
public class CragIdConfiguration {

  private static final Logger log = LoggerFactory.getLogger(CragIdConfiguration.class);

  private final CragIdProperties properties;

  public CragIdConfiguration(CragIdProperties properties) {
    this.properties = properties;
  }

  @Bean
  SnowflakeLayout snowflakeLayout() {
    return new SnowflakeLayout();
  }

  @Bean
  MonotonicClock monotonicClock() {
    return new SystemMonotonicClock();
  }

  @Bean
  CragIdParser cragIdParser(SnowflakeLayout layout) {
    return new DefaultCragIdParser(layout);
  }

  @Bean
  RedisWorkerLeaseRepository leaseRepository(StringRedisTemplate redis) {
    return new RedisWorkerLeaseRepository(redis);
  }

  @Bean
  CragIdGenerator cragIdGenerator(
      SnowflakeLayout layout, MonotonicClock clock, RedisWorkerLeaseRepository leaseRepository) {
    Map<IdEntityType, RedisWorkerLease> leaseMap = new ConcurrentHashMap<>();
    RedisBackedCragIdGenerator.LeaseProvider provider = leaseMap::get;
    return new RedisBackedCragIdGenerator(
        provider, layout, clock, properties.getRollbackThresholdMillis());
  }

  @Bean
  HealthIndicator cragIdHealthIndicator() {
    return new CragIdHealthIndicator(this::getLeaseSnapshot, properties.getRequiredEntities());
  }

  /**
   * Schedules periodic lease renewal for required entity types.
   *
   * <p>The scheduler tries to acquire a lease for each required entity type that doesn't have one
   * yet, and renews leases that are already active. Creates its own {@link TaskScheduler} so that
   * services without {@code @EnableScheduling} still get lease maintenance.
   */
  @Bean
  Object cragIdLeaseScheduler(RedisWorkerLeaseRepository leaseRepository) {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("crag-id-lease-");
    scheduler.initialize();
    scheduler.scheduleAtFixedRate(
        () -> maintainLeases(leaseRepository), properties.getRenewInterval());
    log.info(
        "Crag ID lease renewal scheduled every {}ms for domain {}",
        properties.getRenewInterval().toMillis(),
        properties.getServiceDomain());
    return "cragIdLeaseScheduler";
  }

  // ── lease pool ──────────────────────────────────────────────────────────

  private final Map<IdEntityType, RedisWorkerLease> leaseMap = new ConcurrentHashMap<>();

  private RedisWorkerLease getLeaseSnapshot(IdEntityType entityType) {
    return leaseMap.get(entityType);
  }

  private void maintainLeases(RedisWorkerLeaseRepository repository) {
    for (IdEntityType entityType : properties.getRequiredEntities()) {
      try {
        RedisWorkerLease lease = leaseMap.get(entityType);
        if (lease != null && lease.isActive()) {
          // Already active — try to renew
          boolean ok = lease.renew();
          if (!ok) {
            leaseMap.remove(entityType);
            log.warn("Lease lost for entity {}", entityType);
          }
        } else {
          // Not active — try to acquire
          Optional<Integer> slot =
              repository.findAvailableSlot(properties.getServiceDomain(), entityType);
          if (slot.isEmpty()) {
            log.debug("No available worker slot for entity {}", entityType);
            continue;
          }
          RedisWorkerLease newLease =
              new RedisWorkerLease(
                  repository,
                  properties.getServiceDomain(),
                  entityType,
                  slot.get(),
                  properties.getLeaseTtl());
          if (newLease.acquire()) {
            leaseMap.put(entityType, newLease);
          }
        }
      } catch (Exception e) {
        log.error("Lease maintenance failed for entity {}", entityType, e);
      }
    }
  }
}
