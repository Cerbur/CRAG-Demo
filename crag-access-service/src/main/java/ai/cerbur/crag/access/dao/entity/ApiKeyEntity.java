package ai.cerbur.crag.access.dao.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * api_key 持久化实体，对应 access schema 的 {@code api_key} 表。
 *
 * <p>单 Key 绑定单 KnowledgeBase。完整 Key 格式 {@code crag_<前缀>_<秘密>}，永不落库；只保存可检索 key_prefix 与
 * secret_hmac（独立 Pepper 的 HMAC-SHA-256）。状态 ACTIVE/DISABLED/REVOKED/EXPIRED；DISABLED 可恢复，REVOKED
 * 不可恢复。 {@code version} 为乐观锁版本，状态变更通过版本 CAS 推进。
 */
@Entity
@Table(name = "api_key")
public class ApiKeyEntity {

  public static final String STATUS_ACTIVE = "ACTIVE";
  public static final String STATUS_DISABLED = "DISABLED";
  public static final String STATUS_REVOKED = "REVOKED";
  public static final String STATUS_EXPIRED = "EXPIRED";

  @Id
  @Column(name = "api_key_id", nullable = false, updatable = false)
  private long apiKeyId;

  @Column(name = "tenant_id", nullable = false)
  private long tenantId;

  @Column(name = "knowledge_base_id", nullable = false)
  private long knowledgeBaseId;

  @Column(name = "name", nullable = false)
  private String name;

  @Column(name = "key_prefix", nullable = false)
  private String keyPrefix;

  @Column(name = "secret_hmac", nullable = false)
  private String secretHmac;

  @Column(name = "status", nullable = false)
  private String status;

  @Column(name = "created_by_user_id", nullable = false)
  private long createdByUserId;

  @Column(name = "last_used_at")
  private LocalDateTime lastUsedAt;

  @Column(name = "expires_at", nullable = false)
  private LocalDateTime expiresAt;

  @Column(name = "disabled_at")
  private LocalDateTime disabledAt;

  @Column(name = "revoked_at")
  private LocalDateTime revokedAt;

  @Column(name = "rotated_from")
  private Long rotatedFrom;

  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private LocalDateTime updatedAt;

  @Column(name = "version", nullable = false)
  private long version;

  protected ApiKeyEntity() {}

  /** 创建 ACTIVE Key；完整秘密只在调用方持有，本实体只保存前缀与 HMAC。 */
  public static ApiKeyEntity create(
      long apiKeyId,
      long tenantId,
      long knowledgeBaseId,
      String name,
      String keyPrefix,
      String secretHmac,
      long createdByUserId,
      LocalDateTime expiresAt) {
    ApiKeyEntity entity = new ApiKeyEntity();
    entity.apiKeyId = apiKeyId;
    entity.tenantId = tenantId;
    entity.knowledgeBaseId = knowledgeBaseId;
    entity.name = name;
    entity.keyPrefix = keyPrefix;
    entity.secretHmac = secretHmac;
    entity.status = STATUS_ACTIVE;
    entity.createdByUserId = createdByUserId;
    entity.expiresAt = expiresAt;
    entity.version = 0L;
    LocalDateTime now = LocalDateTime.now();
    entity.createdAt = now;
    entity.updatedAt = now;
    return entity;
  }

  public long getApiKeyId() {
    return apiKeyId;
  }

  public long getTenantId() {
    return tenantId;
  }

  public long getKnowledgeBaseId() {
    return knowledgeBaseId;
  }

  public String getName() {
    return name;
  }

  public String getKeyPrefix() {
    return keyPrefix;
  }

  public String getSecretHmac() {
    return secretHmac;
  }

  public String getStatus() {
    return status;
  }

  public long getCreatedByUserId() {
    return createdByUserId;
  }

  public LocalDateTime getLastUsedAt() {
    return lastUsedAt;
  }

  public LocalDateTime getExpiresAt() {
    return expiresAt;
  }

  public LocalDateTime getDisabledAt() {
    return disabledAt;
  }

  public LocalDateTime getRevokedAt() {
    return revokedAt;
  }

  public Long getRotatedFrom() {
    return rotatedFrom;
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

  public void setLastUsedAt(LocalDateTime lastUsedAt) {
    this.lastUsedAt = lastUsedAt;
  }

  public void setDisabledAt(LocalDateTime disabledAt) {
    this.disabledAt = disabledAt;
  }

  public void setRevokedAt(LocalDateTime revokedAt) {
    this.revokedAt = revokedAt;
  }

  public void setRotatedFrom(Long rotatedFrom) {
    this.rotatedFrom = rotatedFrom;
  }

  public void setUpdatedAt(LocalDateTime updatedAt) {
    this.updatedAt = updatedAt;
  }

  public void setVersion(long version) {
    this.version = version;
  }
}
