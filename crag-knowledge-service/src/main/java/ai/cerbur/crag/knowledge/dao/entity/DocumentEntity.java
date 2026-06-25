package ai.cerbur.crag.knowledge.dao.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * Document 持久化实体，对应 knowledge schema 的 {@code document} 表。
 *
 * <p>上传成功后创建为 {@code PENDING}，内容不可变；{@code PROCESSING / READY / FAILED} 由后续 RAG 状态回传阶段引入。 {@code
 * operationVersion} 是文档逻辑操作版本（上传时为 1），与行级 CAS {@code version} 不同。
 */
@Entity
@Table(name = "document")
public class DocumentEntity {

  /** 摄取状态展示值，首版创建时为 PENDING。 */
  public static final String INGESTION_STATUS_PENDING = "PENDING";

  /** 上传创建时的初始逻辑操作版本。 */
  public static final long INITIAL_OPERATION_VERSION = 1L;

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "doc_id", nullable = false, updatable = false)
  private Long docId;

  @Column(name = "knowledge_base_id", nullable = false)
  private long knowledgeBaseId;

  @Column(name = "tenant_id", nullable = false)
  private long tenantId;

  @Column(name = "uploaded_by_user_id", nullable = false)
  private long uploadedByUserId;

  @Column(name = "original_filename", nullable = false)
  private String originalFilename;

  @Column(name = "file_type", nullable = false)
  private String fileType;

  @Column(name = "size_bytes", nullable = false)
  private long sizeBytes;

  @Column(name = "sha256", nullable = false)
  private String sha256;

  @Column(name = "ingestion_status", nullable = false)
  private String ingestionStatus;

  @Column(name = "operation_version", nullable = false)
  private long operationVersion;

  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private LocalDateTime updatedAt;

  @Column(name = "version", nullable = false)
  private Long version;

  protected DocumentEntity() {}

  /** 创建 PENDING 文档，operationVersion 为 1，version 为 0。 */
  public static DocumentEntity create(
      long knowledgeBaseId,
      long tenantId,
      long uploadedByUserId,
      String originalFilename,
      String fileType,
      long sizeBytes,
      String sha256) {
    DocumentEntity entity = new DocumentEntity();
    entity.knowledgeBaseId = knowledgeBaseId;
    entity.tenantId = tenantId;
    entity.uploadedByUserId = uploadedByUserId;
    entity.originalFilename = originalFilename;
    entity.fileType = fileType;
    entity.sizeBytes = sizeBytes;
    entity.sha256 = sha256;
    entity.ingestionStatus = INGESTION_STATUS_PENDING;
    entity.operationVersion = INITIAL_OPERATION_VERSION;
    entity.version = 0L;
    LocalDateTime now = LocalDateTime.now();
    entity.createdAt = now;
    entity.updatedAt = now;
    return entity;
  }

  public Long getDocId() {
    return docId;
  }

  public long getKnowledgeBaseId() {
    return knowledgeBaseId;
  }

  public long getTenantId() {
    return tenantId;
  }

  public long getUploadedByUserId() {
    return uploadedByUserId;
  }

  public String getOriginalFilename() {
    return originalFilename;
  }

  public String getFileType() {
    return fileType;
  }

  public long getSizeBytes() {
    return sizeBytes;
  }

  public String getSha256() {
    return sha256;
  }

  public String getIngestionStatus() {
    return ingestionStatus;
  }

  public long getOperationVersion() {
    return operationVersion;
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
