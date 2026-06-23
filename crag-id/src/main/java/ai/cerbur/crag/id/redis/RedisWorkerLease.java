package ai.cerbur.crag.id.redis;

import ai.cerbur.crag.id.api.IdEntityType;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * State machine for a single (service domain, entity type, worker slot) Redis lease.
 *
 * <h3>Lifecycle</h3>
 *
 * <pre>
 * UNACQUIRED ── acquire() ──→ ACTIVE ── renew() ──→ ACTIVE
 *                                  │
 *                                  ├── renew() fails → LOST
 *                                  ├── release() → UNACQUIRED
 *                                  └── TTL expires → LOST
 * </pre>
 *
 * <p>The owner token is a random UUID that identifies this process instance. It is never logged.
 */
public class RedisWorkerLease {

  private static final Logger log = LoggerFactory.getLogger(RedisWorkerLease.class);

  private final RedisWorkerLeaseRepository repository;
  private final String serviceDomain;
  private final IdEntityType entityType;
  private final int workerId;
  private final String ownerToken;
  private final Duration leaseTtl;

  private volatile boolean active;

  /**
   * Create a lease for the given slot. The lease is NOT acquired on construction — call {@link
   * #acquire()} first.
   */
  public RedisWorkerLease(
      RedisWorkerLeaseRepository repository,
      String serviceDomain,
      IdEntityType entityType,
      int workerId,
      Duration leaseTtl) {
    this.repository = repository;
    this.serviceDomain = serviceDomain;
    this.entityType = entityType;
    this.workerId = workerId;
    this.ownerToken = UUID.randomUUID().toString();
    this.leaseTtl = leaseTtl;
  }

  /**
   * Try to acquire the lease via SET NX.
   *
   * @return {@code true} if the lease was acquired
   */
  public boolean acquire() {
    boolean ok = repository.tryAcquire(serviceDomain, entityType, workerId, ownerToken, leaseTtl);
    if (ok) {
      active = true;
      log.info(
          "Worker lease acquired: domain={} entity={} worker={}",
          serviceDomain,
          entityType,
          workerId);
    }
    return ok;
  }

  /**
   * Renew the lease — extends TTL only if this process still owns it.
   *
   * @return {@code true} if still active after the call
   */
  public boolean renew() {
    if (!active) {
      return false;
    }
    boolean ok = repository.renew(serviceDomain, entityType, workerId, ownerToken, leaseTtl);
    if (!ok) {
      active = false;
      log.warn(
          "Worker lease lost during renew: domain={} entity={} worker={}",
          serviceDomain,
          entityType,
          workerId);
    }
    return ok;
  }

  /**
   * Release the lease — delete the Redis key only if we still own it.
   *
   * @return {@code true} if the key was deleted
   */
  public boolean release() {
    boolean ok = repository.release(serviceDomain, entityType, workerId, ownerToken);
    active = false;
    if (ok) {
      log.info(
          "Worker lease released: domain={} entity={} worker={}",
          serviceDomain,
          entityType,
          workerId);
    }
    return ok;
  }

  /** Whether the lease is currently believed to be active. */
  public boolean isActive() {
    return active;
  }

  public String serviceDomain() {
    return serviceDomain;
  }

  public IdEntityType entityType() {
    return entityType;
  }

  public int workerId() {
    return workerId;
  }

  public String ownerToken() {
    return ownerToken;
  }
}
