package ai.cerbur.crag.id.redis;

import ai.cerbur.crag.id.api.IdEntityType;
import java.time.Duration;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Repository for Redis-backed worker lease key operations.
 *
 * <p>Key format: {@code crag:id:{serviceDomain}:{entityType}:{workerId}}. Each key stores the owner
 * token as its Redis string value. All mutating operations use atomic Redis commands or Lua scripts
 * to prevent races.
 *
 * <p>Worker slots range from 0 to {@code maxSlots - 1} (default 16).
 */
public class RedisWorkerLeaseRepository {

  private static final int DEFAULT_MAX_SLOTS = 16;

  private final LeaseOps ops;

  /** Production constructor using {@link StringRedisTemplate}. */
  public RedisWorkerLeaseRepository(StringRedisTemplate redis) {
    this(new RedisTemplateLeaseOps(redis));
  }

  /** Test-only constructor for {@link LeaseOps} fakes. */
  RedisWorkerLeaseRepository(LeaseOps ops) {
    this.ops = ops;
  }

  static String leaseKey(String serviceDomain, IdEntityType entityType, int workerId) {
    return "crag:id:" + serviceDomain + ":" + entityType.name() + ":" + workerId;
  }

  /**
   * Try to acquire a worker slot using SET NX.
   *
   * @return {@code true} if the key was newly created
   */
  public boolean tryAcquire(
      String serviceDomain,
      IdEntityType entityType,
      int workerId,
      String ownerToken,
      Duration ttl) {
    return ops.setIfAbsent(
        leaseKey(serviceDomain, entityType, workerId), ownerToken, ttl.toMillis());
  }

  /**
   * Renew the lease TTL — only succeeds if the current value matches {@code ownerToken}.
   *
   * @return {@code true} if the TTL was extended
   */
  public boolean renew(
      String serviceDomain,
      IdEntityType entityType,
      int workerId,
      String ownerToken,
      Duration ttl) {
    return ops.compareAndSet(
        leaseKey(serviceDomain, entityType, workerId), ownerToken, ownerToken, ttl.toMillis());
  }

  /**
   * Release the lease — only deletes the key if the current value matches {@code ownerToken}.
   *
   * @return {@code true} if the key was deleted
   */
  public boolean release(
      String serviceDomain, IdEntityType entityType, int workerId, String ownerToken) {
    return ops.compareAndDelete(leaseKey(serviceDomain, entityType, workerId), ownerToken);
  }

  /**
   * Scan worker slots {@code 0 .. maxSlots-1} and return the first slot whose key is absent or
   * expired.
   */
  public Optional<Integer> findAvailableSlot(
      String serviceDomain, IdEntityType entityType, int maxSlots) {
    for (int i = 0; i < maxSlots; i++) {
      if (ops.get(leaseKey(serviceDomain, entityType, i)) == null) {
        return Optional.of(i);
      }
    }
    return Optional.empty();
  }

  /** Convenience overload using the default 16 slots. */
  public Optional<Integer> findAvailableSlot(String serviceDomain, IdEntityType entityType) {
    return findAvailableSlot(serviceDomain, entityType, DEFAULT_MAX_SLOTS);
  }
}
