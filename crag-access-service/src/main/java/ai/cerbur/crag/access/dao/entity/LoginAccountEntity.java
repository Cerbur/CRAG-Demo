package ai.cerbur.crag.access.dao.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * login_account 持久化实体，对应 access schema 的 {@code login_account} 表。
 *
 * <p>登录凭据。首期 account_type 固定 {@link #ACCOUNT_TYPE_USERNAME}；(account_type, normalized_identifier)
 * 全局唯一。 credential_hash 为 Argon2id 编码串，禁止明文密码。Username 规范化后全局唯一且 plan20 不支持修改。
 */
@Entity
@Table(
    name = "login_account",
    uniqueConstraints = {
      @jakarta.persistence.UniqueConstraint(
          name = "uq_login_account_norm",
          columnNames = {"account_type", "normalized_identifier"})
    })
public class LoginAccountEntity {

  public static final String ACCOUNT_TYPE_USERNAME = "USERNAME";
  public static final String STATUS_ACTIVE = "ACTIVE";
  public static final String STATUS_DISABLED = "DISABLED";

  @Id
  @Column(name = "account_id", nullable = false, updatable = false)
  private long accountId;

  @Column(name = "user_id", nullable = false, updatable = false)
  private long userId;

  @Column(name = "account_type", nullable = false)
  private String accountType;

  @Column(name = "login_identifier", nullable = false)
  private String loginIdentifier;

  @Column(name = "normalized_identifier", nullable = false)
  private String normalizedIdentifier;

  @Column(name = "credential_hash", nullable = false)
  private String credentialHash;

  @Column(name = "status", nullable = false)
  private String status;

  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private LocalDateTime updatedAt;

  @Column(name = "version", nullable = false)
  private long version;

  protected LoginAccountEntity() {}

  /** 创建 ACTIVE USERNAME 账号；normalized 为规范化后的 Username，credentialHash 为 Argon2id 编码串。 */
  public static LoginAccountEntity create(
      long accountId,
      long userId,
      String loginIdentifier,
      String normalizedIdentifier,
      String credentialHash) {
    LoginAccountEntity entity = new LoginAccountEntity();
    entity.accountId = accountId;
    entity.userId = userId;
    entity.accountType = ACCOUNT_TYPE_USERNAME;
    entity.loginIdentifier = loginIdentifier;
    entity.normalizedIdentifier = normalizedIdentifier;
    entity.credentialHash = credentialHash;
    entity.status = STATUS_ACTIVE;
    entity.version = 0L;
    LocalDateTime now = LocalDateTime.now();
    entity.createdAt = now;
    entity.updatedAt = now;
    return entity;
  }

  public long getAccountId() {
    return accountId;
  }

  public long getUserId() {
    return userId;
  }

  public String getAccountType() {
    return accountType;
  }

  public String getLoginIdentifier() {
    return loginIdentifier;
  }

  public String getNormalizedIdentifier() {
    return normalizedIdentifier;
  }

  public String getCredentialHash() {
    return credentialHash;
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

  public void setCredentialHash(String credentialHash) {
    this.credentialHash = credentialHash;
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
