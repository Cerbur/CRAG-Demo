package ai.cerbur.crag.access.dao.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * tenant_membership 持久化实体，对应 access schema 的 {@code tenant_membership} 表。
 *
 * <p>(tenant_id, user_id) 唯一；同一用户被移除后再次加入复用本行并设为 MEMBER。角色为 OWNER/MEMBER，状态为 ACTIVE/REMOVED。 {@code
 * version} 为乐观锁版本，成员变更通过版本 CAS 推进。
 */
@Entity
@Table(
    name = "tenant_membership",
    uniqueConstraints = {
      @jakarta.persistence.UniqueConstraint(
          name = "uq_tenant_membership",
          columnNames = {"tenant_id", "user_id"})
    })
public class TenantMembershipEntity {

  public static final String ROLE_OWNER = "OWNER";
  public static final String ROLE_MEMBER = "MEMBER";
  public static final String STATUS_ACTIVE = "ACTIVE";
  public static final String STATUS_REMOVED = "REMOVED";

  @Id
  @Column(name = "membership_id", nullable = false, updatable = false)
  private long membershipId;

  @Column(name = "tenant_id", nullable = false)
  private long tenantId;

  @Column(name = "user_id", nullable = false)
  private long userId;

  @Column(name = "role", nullable = false)
  private String role;

  @Column(name = "status", nullable = false)
  private String status;

  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private LocalDateTime updatedAt;

  @Column(name = "version", nullable = false)
  private long version;

  protected TenantMembershipEntity() {}

  /** 创建 ACTIVE OWNER 成员关系（用于默认 Tenant 的注册者）。 */
  public static TenantMembershipEntity createOwner(long membershipId, long tenantId, long userId) {
    return create(membershipId, tenantId, userId, ROLE_OWNER, STATUS_ACTIVE);
  }

  public static TenantMembershipEntity create(
      long membershipId, long tenantId, long userId, String role, String status) {
    TenantMembershipEntity entity = new TenantMembershipEntity();
    entity.membershipId = membershipId;
    entity.tenantId = tenantId;
    entity.userId = userId;
    entity.role = role;
    entity.status = status;
    entity.version = 0L;
    LocalDateTime now = LocalDateTime.now();
    entity.createdAt = now;
    entity.updatedAt = now;
    return entity;
  }

  public long getMembershipId() {
    return membershipId;
  }

  public long getTenantId() {
    return tenantId;
  }

  public long getUserId() {
    return userId;
  }

  public String getRole() {
    return role;
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

  public void setRole(String role) {
    this.role = role;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public void setUpdatedAt(LocalDateTime updatedAt) {
    this.updatedAt = updatedAt;
  }

  public void setVersion(long version) {
    this.version = version;
  }
}
