package ai.cerbur.crag.access.dao.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * tenant 持久化实体，对应 access schema 的 {@code tenant} 表。注册时由系统创建默认 Tenant。
 *
 * <p>tenant_id 为 Snowflake 标识；{@code version} 为乐观锁版本。
 */
@Entity
@Table(name = "tenant")
public class TenantEntity {

  public static final String STATUS_ACTIVE = "ACTIVE";

  @Id
  @Column(name = "tenant_id", nullable = false, updatable = false)
  private long tenantId;

  @Column(name = "name", nullable = false)
  private String name;

  @Column(name = "status", nullable = false)
  private String status;

  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private LocalDateTime updatedAt;

  @Column(name = "version", nullable = false)
  private long version;

  protected TenantEntity() {}

  /** 创建 ACTIVE Tenant。 */
  public static TenantEntity create(long tenantId, String name) {
    TenantEntity entity = new TenantEntity();
    entity.tenantId = tenantId;
    entity.name = name;
    entity.status = STATUS_ACTIVE;
    entity.version = 0L;
    LocalDateTime now = LocalDateTime.now();
    entity.createdAt = now;
    entity.updatedAt = now;
    return entity;
  }

  public long getTenantId() {
    return tenantId;
  }

  public String getName() {
    return name;
  }

  public String getStatus() {
    return status;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public LocalDateTime getUpdatedAt() {
    return updatedAt;
  }

  public long getVersion() {
    return version;
  }

  public void setUpdatedAt(LocalDateTime updatedAt) {
    this.updatedAt = updatedAt;
  }

  public void setVersion(long version) {
    this.version = version;
  }
}
