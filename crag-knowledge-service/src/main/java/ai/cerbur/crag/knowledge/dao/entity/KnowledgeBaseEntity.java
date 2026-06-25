package ai.cerbur.crag.knowledge.dao.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * KnowledgeBase 持久化实体，对应 knowledge schema 的 {@code knowledge_base} 表。
 *
 * <p>知识库归 Tenant 所有；查询必须携带 {@code tenantId} 以避免跨租户泄漏。主键由数据库 identity 列生成（plan_18 文件边界不含 crag-id，未采用
 * Snowflake）。{@code version} 为乐观锁版本，CAS 更新时手动递增。
 */
@Entity
@Table(name = "knowledge_base")
public class KnowledgeBaseEntity {

  /** 状态展示值，首版只实现 ACTIVE。 */
  public static final String STATUS_ACTIVE = "ACTIVE";

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "knowledge_base_id", nullable = false, updatable = false)
  private Long knowledgeBaseId;

  @Column(name = "tenant_id", nullable = false)
  private long tenantId;

  @Column(name = "name", nullable = false)
  private String name;

  @Column(name = "created_by_user_id", nullable = false)
  private long createdByUserId;

  @Column(name = "status", nullable = false)
  private String status;

  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private LocalDateTime updatedAt;

  @Column(name = "version", nullable = false)
  private Long version;

  protected KnowledgeBaseEntity() {}

  /** 创建 ACTIVE 知识库；version 初始化为 0，时间戳设为当前时刻。 */
  public static KnowledgeBaseEntity create(long tenantId, String name, long createdByUserId) {
    KnowledgeBaseEntity entity = new KnowledgeBaseEntity();
    entity.tenantId = tenantId;
    entity.name = name;
    entity.createdByUserId = createdByUserId;
    entity.status = STATUS_ACTIVE;
    entity.version = 0L;
    LocalDateTime now = LocalDateTime.now();
    entity.createdAt = now;
    entity.updatedAt = now;
    return entity;
  }

  public Long getKnowledgeBaseId() {
    return knowledgeBaseId;
  }

  public long getTenantId() {
    return tenantId;
  }

  public String getName() {
    return name;
  }

  public long getCreatedByUserId() {
    return createdByUserId;
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

  public Long getVersion() {
    return version;
  }

  public void setVersion(Long version) {
    this.version = version;
  }
}
