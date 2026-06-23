package ai.cerbur.crag.id.internal;

import ai.cerbur.crag.id.api.CragIdGenerator;
import ai.cerbur.crag.id.api.IdEntityType;
import ai.cerbur.crag.id.redis.RedisWorkerLease;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link CragIdGenerator} backed by per-entity-type {@link SnowflakeSequence} and Redis worker
 * lease.
 *
 * <p>Each (entity type) combination is lazily initialised on the first {@code nextId()} call. The
 * lease must be active and the clock must not have rolled back beyond the configured threshold.
 *
 * <h3>Thread safety</h3>
 *
 * The per-entity {@link SnowflakeSequence#nextId()} is {@code synchronized}; the generator itself
 * uses a {@link ConcurrentHashMap} for the shared sequence map.
 */
public final class RedisBackedCragIdGenerator implements CragIdGenerator {

  private static final Logger log = LoggerFactory.getLogger(RedisBackedCragIdGenerator.class);

  private final LeaseProvider leaseProvider;
  private final SnowflakeLayout layout;
  private final MonotonicClock clock;
  private final long rollbackThresholdMillis;
  private final Map<IdEntityType, SnowflakeSequence> sequences;

  /**
   * Functional interface for obtaining or checking a lease for a given entity type.
   *
   * <p>Implemented by {@code CragIdConfiguration} to bridge the Spring-managed lease pool and the
   * pure generator.
   */
  @FunctionalInterface
  public interface LeaseProvider {
    /**
     * Return the active lease for the given entity type, or {@code null} if no lease is currently
     * held.
     */
    RedisWorkerLease getLease(IdEntityType entityType);
  }

  public RedisBackedCragIdGenerator(
      LeaseProvider leaseProvider,
      SnowflakeLayout layout,
      MonotonicClock clock,
      long rollbackThresholdMillis) {
    this.leaseProvider = leaseProvider;
    this.layout = layout;
    this.clock = clock;
    this.rollbackThresholdMillis = rollbackThresholdMillis;
    this.sequences = new ConcurrentHashMap<>();
  }

  @Override
  public long nextId(IdEntityType entityType) {
    RedisWorkerLease lease = leaseProvider.getLease(entityType);
    if (lease == null || !lease.isActive()) {
      throw new IllegalStateException(
          "No active lease for entity type " + entityType + " — generator not ready");
    }

    SnowflakeSequence seq =
        sequences.computeIfAbsent(
            entityType,
            et ->
                new SnowflakeSequence(
                    lease.workerId(), et, layout, clock, rollbackThresholdMillis));

    try {
      return seq.nextId();
    } catch (ClockRollbackException e) {
      log.error(
          "Clock rollback exceeded threshold for entity {}: {}ms",
          entityType,
          e.getRollbackMillis());
      throw e;
    }
  }

  /** For diagnostics: number of entity types that currently have an active sequence. */
  public int activeSequenceCount() {
    return sequences.size();
  }
}
