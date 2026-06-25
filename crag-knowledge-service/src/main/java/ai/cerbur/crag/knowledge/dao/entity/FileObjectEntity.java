package ai.cerbur.crag.knowledge.dao.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * FileObject 持久化实体，对应 knowledge schema 的 {@code file_object} 表。
 *
 * <p>{@code storageKey} 是 Knowledge 内部文件存储标识，禁止出现在 gRPC、HTTP 响应、事件 payload 或业务日志中。文件存储名由服务端生成，
 * 不拼接原始文件名。首版上传成功即为 {@code STORED}。
 */
@Entity
@Table(name = "file_object")
public class FileObjectEntity {

  /** 存储状态展示值，首版只实现 STORED。 */
  public static final String STORAGE_STATUS_STORED = "STORED";

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "file_object_id", nullable = false, updatable = false)
  private Long fileObjectId;

  @Column(name = "doc_id", nullable = false)
  private long docId;

  @Column(name = "storage_key", nullable = false)
  private String storageKey;

  @Column(name = "size_bytes", nullable = false)
  private long sizeBytes;

  @Column(name = "sha256", nullable = false)
  private String sha256;

  @Column(name = "storage_status", nullable = false)
  private String storageStatus;

  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private LocalDateTime updatedAt;

  @Column(name = "version", nullable = false)
  private Long version;

  protected FileObjectEntity() {}

  /** 创建 STORED 文件对象，version 为 0。 */
  public static FileObjectEntity create(
      long docId, String storageKey, long sizeBytes, String sha256) {
    FileObjectEntity entity = new FileObjectEntity();
    entity.docId = docId;
    entity.storageKey = storageKey;
    entity.sizeBytes = sizeBytes;
    entity.sha256 = sha256;
    entity.storageStatus = STORAGE_STATUS_STORED;
    entity.version = 0L;
    LocalDateTime now = LocalDateTime.now();
    entity.createdAt = now;
    entity.updatedAt = now;
    return entity;
  }

  public Long getFileObjectId() {
    return fileObjectId;
  }

  public long getDocId() {
    return docId;
  }

  public String getStorageKey() {
    return storageKey;
  }

  public long getSizeBytes() {
    return sizeBytes;
  }

  public String getSha256() {
    return sha256;
  }

  public String getStorageStatus() {
    return storageStatus;
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
}
