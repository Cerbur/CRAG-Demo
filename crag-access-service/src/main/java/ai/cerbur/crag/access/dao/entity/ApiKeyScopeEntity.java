package ai.cerbur.crag.access.dao.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * api_key_scope 持久化实体，对应 access schema 的 {@code api_key_scope} 表。
 *
 * <p>KnowledgeBase 最小授权投影，主键即 knowledge_base_id，不保存 KnowledgeBase 业务字段。状态为 ACTIVE/BLOCKED， {@code
 * BLOCKED} 为终态。{@code version} 为乐观锁版本。
 */
@Entity
@Table(name = "api_key_scope")
public class ApiKeyScopeEntity {

  public static final String STATUS_ACTIVE = "ACTIVE";
  public static final String STATUS_BLOCKED = "BLOCKED";

  @Id
  @Column(name = "knowledge_base_id", nullable = false, updatable = false)
  private long knowledgeBaseId;

  @Column(name = "tenant_id", nullable = false, updatable = false)
  private long tenantId;

  @Column(name = "status", nullable = false)
  private String status;

  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private LocalDateTime updatedAt;

  @Column(name = "version", nullable = false)
  private long version;

  protected ApiKeyScopeEntity() {}

  /** 创建 ACTIVE Scope。 */
  public static ApiKeyScopeEntity create(long knowledgeBaseId, long tenantId) {
    ApiKeyScopeEntity entity = new ApiKeyScopeEntity();
    entity.knowledgeBaseId = knowledgeBaseId;
    entity.tenantId = tenantId;
    entity.status = STATUS_ACTIVE;
    entity.version = 0L;
    LocalDateTime now = LocalDateTime.now();
    entity.createdAt = now;
    entity.updatedAt = now;
    return entity;
  }

  public long getKnowledgeBaseId() {
    return knowledgeBaseId;
  }

  public long getTenantId() {
    return tenantId;
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
