package ai.cerbur.crag.access.dao.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * refresh_session 持久化实体，对应 access schema 的 {@code refresh_session} 表。
 *
 * <p>每次 Refresh Token 签发保存一行。token_hmac 为独立 Pepper 的 HMAC-SHA-256，唯一可检索，不保存原文。状态包含
 * ACTIVE/ROTATED/REVOKED/EXPIRED；ROTATED Token 再次出现视为复用攻击，撤销整个 Family。session_id 与 family_id 均为
 * Snowflake。
 *
 * <p>{@code version} 为乐观锁版本，轮换通过版本 CAS 保证单 Token 单次成功。
 */
@Entity
@Table(name = "refresh_session")
public class RefreshSessionEntity {

  public static final String STATUS_ACTIVE = "ACTIVE";
  public static final String STATUS_ROTATED = "ROTATED";
  public static final String STATUS_REVOKED = "REVOKED";
  public static final String STATUS_EXPIRED = "EXPIRED";

  @Id
  @Column(name = "session_id", nullable = false, updatable = false)
  private long sessionId;

  @Column(name = "family_id", nullable = false)
  private long familyId;

  @Column(name = "user_id", nullable = false)
  private long userId;

  @Column(name = "token_hmac", nullable = false)
  private String tokenHmac;

  @Column(name = "status", nullable = false)
  private String status;

  @Column(name = "issued_at", nullable = false)
  private LocalDateTime issuedAt;

  @Column(name = "expires_at", nullable = false)
  private LocalDateTime expiresAt;

  @Column(name = "rotated_at")
  private LocalDateTime rotatedAt;

  @Column(name = "revoked_at")
  private LocalDateTime revokedAt;

  @Column(name = "replaced_by")
  private Long replacedBy;

  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private LocalDateTime updatedAt;

  @Column(name = "version", nullable = false)
  private long version;

  protected RefreshSessionEntity() {}

  /** 创建 ACTIVE Refresh Session。 */
  public static RefreshSessionEntity create(
      long sessionId,
      long familyId,
      long userId,
      String tokenHmac,
      LocalDateTime issuedAt,
      LocalDateTime expiresAt) {
    RefreshSessionEntity entity = new RefreshSessionEntity();
    entity.sessionId = sessionId;
    entity.familyId = familyId;
    entity.userId = userId;
    entity.tokenHmac = tokenHmac;
    entity.status = STATUS_ACTIVE;
    entity.issuedAt = issuedAt;
    entity.expiresAt = expiresAt;
    entity.version = 0L;
    entity.createdAt = issuedAt;
    entity.updatedAt = issuedAt;
    return entity;
  }

  public long getSessionId() {
    return sessionId;
  }

  public long getFamilyId() {
    return familyId;
  }

  public long getUserId() {
    return userId;
  }

  public String getTokenHmac() {
    return tokenHmac;
  }

  public String getStatus() {
    return status;
  }

  public LocalDateTime getIssuedAt() {
    return issuedAt;
  }

  public LocalDateTime getExpiresAt() {
    return expiresAt;
  }

  public LocalDateTime getRotatedAt() {
    return rotatedAt;
  }

  public LocalDateTime getRevokedAt() {
    return revokedAt;
  }

  public Long getReplacedBy() {
    return replacedBy;
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

  public void setRotatedAt(LocalDateTime rotatedAt) {
    this.rotatedAt = rotatedAt;
  }

  public void setRevokedAt(LocalDateTime revokedAt) {
    this.revokedAt = revokedAt;
  }

  public void setReplacedBy(Long replacedBy) {
    this.replacedBy = replacedBy;
  }

  public void setUpdatedAt(LocalDateTime updatedAt) {
    this.updatedAt = updatedAt;
  }

  public void setVersion(long version) {
    this.version = version;
  }
}
