package ai.cerbur.crag.access.dao.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * platform_user 持久化实体，对应 access schema 的 {@code platform_user} 表。
 *
 * <p>user_id 是永久 Snowflake 身份标识，由 {@code CragIdGenerator(USER)} 分配；Nickname 为可修改展示名，不参与登录。 {@code
 * version} 为乐观锁版本，CAS 更新时手动递增。
 */
@Entity
@Table(name = "platform_user")
public class PlatformUserEntity {

  public static final String STATUS_ACTIVE = "ACTIVE";
  public static final String STATUS_DISABLED = "DISABLED";

  @Id
  @Column(name = "user_id", nullable = false, updatable = false)
  private long userId;

  @Column(name = "nickname", nullable = false)
  private String nickname;

  @Column(name = "status", nullable = false)
  private String status;

  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private LocalDateTime updatedAt;

  @Column(name = "version", nullable = false)
  private long version;

  protected PlatformUserEntity() {}

  /** 创建 ACTIVE 用户；version 初始化为 0，时间戳设为当前时刻。 */
  public static PlatformUserEntity create(long userId, String nickname) {
    PlatformUserEntity entity = new PlatformUserEntity();
    entity.userId = userId;
    entity.nickname = nickname;
    entity.status = STATUS_ACTIVE;
    entity.version = 0L;
    LocalDateTime now = LocalDateTime.now();
    entity.createdAt = now;
    entity.updatedAt = now;
    return entity;
  }

  public long getUserId() {
    return userId;
  }

  public String getNickname() {
    return nickname;
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

  public void setNickname(String nickname) {
    this.nickname = nickname;
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
