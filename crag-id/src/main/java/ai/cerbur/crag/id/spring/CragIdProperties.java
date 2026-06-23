package ai.cerbur.crag.id.spring;

import ai.cerbur.crag.id.api.IdEntityType;
import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the Crag ID infrastructure, bound under {@code crag.id}.
 *
 * <p>Required entities act as a readiness gate: the generator will not enter healthy state until
 * every required entity type has an active worker lease.
 */
@ConfigurationProperties(prefix = "crag.id")
public class CragIdProperties {

  /** Service domain used in lease key namespaces (e.g. "rag", "access"). */
  private String serviceDomain;

  /**
   * Entity types that this service must have active worker leases for before reporting readiness
   * UP.
   */
  private Set<IdEntityType> requiredEntities = Set.of();

  /** Redis lease TTL. Default 30s. */
  private Duration leaseTtl = Duration.ofSeconds(30);

  /** Interval between lease renewals. Default 10s. */
  private Duration renewInterval = Duration.ofSeconds(10);

  /** Maximum backward clock drift tolerated before fail-fast. Default 5ms. */
  private long rollbackThresholdMillis = 5;

  public String getServiceDomain() {
    return serviceDomain;
  }

  public void setServiceDomain(String serviceDomain) {
    this.serviceDomain = serviceDomain;
  }

  public Set<IdEntityType> getRequiredEntities() {
    return requiredEntities;
  }

  public void setRequiredEntities(Set<IdEntityType> requiredEntities) {
    this.requiredEntities = requiredEntities;
  }

  public Duration getLeaseTtl() {
    return leaseTtl;
  }

  public void setLeaseTtl(Duration leaseTtl) {
    this.leaseTtl = leaseTtl;
  }

  public Duration getRenewInterval() {
    return renewInterval;
  }

  public void setRenewInterval(Duration renewInterval) {
    this.renewInterval = renewInterval;
  }

  public long getRollbackThresholdMillis() {
    return rollbackThresholdMillis;
  }

  public void setRollbackThresholdMillis(long rollbackThresholdMillis) {
    this.rollbackThresholdMillis = rollbackThresholdMillis;
  }
}
