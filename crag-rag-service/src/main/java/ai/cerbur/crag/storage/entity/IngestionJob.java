package ai.cerbur.crag.storage.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;

/**
 * Ingestion Job 实体（Plan 19）—— 记录 RAG 消费 DOC_UPLOADED 后的异步索引任务.
 *
 * <p>业务幂等键为 {@code (docId, operationVersion)}（数据库唯一约束 {@code uq_ingestion_job_doc_version}）。状态机
 * {@code PENDING → PROCESSING → READY / FAILED}；FAILED 为终态，重复事件不自动重跑。job_id 由数据库 IDENTITY 生成， 边界不含
 * crag-id。fileType/sizeBytes/sha256 来自 DOC_UPLOADED payload，仅作审计；failureCategory / failureMessage
 * 只存安全短摘要，禁止透传 SQL、堆栈、文件内容或 storage key.
 *
 * @since 2026-06-27
 */
@Entity
@Table(
    name = "ingestion_job",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uq_ingestion_job_doc_version",
            columnNames = {"doc_id", "operation_version"}))
public class IngestionJob {

  /** Job 主键，数据库 IDENTITY 生成（边界不含 crag-id）. */
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "job_id", nullable = false, updatable = false)
  private Long jobId;

  /** 租户 ID. */
  @Column(name = "tenant_id", nullable = false)
  private long tenantId;

  /** 所属知识库 ID. */
  @Column(name = "knowledge_base_id", nullable = false)
  private long knowledgeBaseId;

  /** 文档 ID（业务幂等键之一）. */
  @Column(name = "doc_id", nullable = false)
  private long docId;

  /** 文档逻辑操作版本（业务幂等键之一）. */
  @Column(name = "operation_version", nullable = false)
  private long operationVersion;

  /** Job 状态. */
  @Column(name = "status", nullable = false)
  @Convert(converter = IngestionJobStatusConverter.class)
  private IngestionJobStatus status;

  /** 文件类型展示值（TXT / MARKDOWN），来自 DOC_UPLOADED payload. */
  @Column(name = "file_type")
  private String fileType;

  /** 文件字节数，来自 DOC_UPLOADED payload. */
  @Column(name = "size_bytes")
  private Long sizeBytes;

  /** 文件 sha256（十六进制小写），来自 DOC_UPLOADED payload. */
  @Column(name = "sha256")
  private String sha256;

  /** 失败分类（安全枚举名），仅 FAILED 时填充. */
  @Column(name = "failure_category")
  private String failureCategory;

  /** 失败安全短摘要，仅 FAILED 时填充；禁止透传敏感信息. */
  @Column(name = "failure_message")
  private String failureMessage;

  /** 进入 PROCESSING 的时间. */
  @Column(name = "started_at")
  private LocalDateTime startedAt;

  /** 进入终态（READY / FAILED）的时间. */
  @Column(name = "completed_at")
  private LocalDateTime completedAt;

  /** 记录创建时间. */
  @Column(name = "created_at", updatable = false)
  private LocalDateTime createdAt;

  /** 记录最后更新时间. */
  @Column(name = "updated_at")
  private LocalDateTime updatedAt;

  /** 乐观锁版本号，自定义 CAS 更新在 WHERE 比对并在 SET 递增. */
  @Column(name = "version")
  private Integer version;

  /**
   * 创建 PENDING Job 的工厂方法.
   *
   * @param tenantId 租户 ID
   * @param knowledgeBaseId 知识库 ID
   * @param docId 文档 ID
   * @param operationVersion 文档操作版本
   * @param fileType 文件类型展示值
   * @param sizeBytes 文件字节数
   * @param sha256 文件 sha256
   * @return PENDING IngestionJob 实体
   */
  public static IngestionJob createPending(
      long tenantId,
      long knowledgeBaseId,
      long docId,
      long operationVersion,
      String fileType,
      long sizeBytes,
      String sha256) {
    IngestionJob job = new IngestionJob();
    job.setTenantId(tenantId);
    job.setKnowledgeBaseId(knowledgeBaseId);
    job.setDocId(docId);
    job.setOperationVersion(operationVersion);
    job.setStatus(IngestionJobStatus.PENDING);
    job.setFileType(fileType);
    job.setSizeBytes(sizeBytes);
    job.setSha256(sha256);
    // 显式设置审计时间：schema 列为 NOT NULL，JPA INSERT 会写入实体字段值（NULL 会违反约束，
    // 数据库 DEFAULT 仅在列被省略时生效），因此工厂方法必须提供非空时间戳.
    LocalDateTime now = LocalDateTime.now();
    job.setCreatedAt(now);
    job.setUpdatedAt(now);
    job.setVersion(0);
    return job;
  }

  public Long getJobId() {
    return jobId;
  }

  public void setJobId(Long jobId) {
    this.jobId = jobId;
  }

  public long getTenantId() {
    return tenantId;
  }

  public void setTenantId(long tenantId) {
    this.tenantId = tenantId;
  }

  public long getKnowledgeBaseId() {
    return knowledgeBaseId;
  }

  public void setKnowledgeBaseId(long knowledgeBaseId) {
    this.knowledgeBaseId = knowledgeBaseId;
  }

  public long getDocId() {
    return docId;
  }

  public void setDocId(long docId) {
    this.docId = docId;
  }

  public long getOperationVersion() {
    return operationVersion;
  }

  public void setOperationVersion(long operationVersion) {
    this.operationVersion = operationVersion;
  }

  public IngestionJobStatus getStatus() {
    return status;
  }

  public void setStatus(IngestionJobStatus status) {
    this.status = status;
  }

  public String getFileType() {
    return fileType;
  }

  public void setFileType(String fileType) {
    this.fileType = fileType;
  }

  public Long getSizeBytes() {
    return sizeBytes;
  }

  public void setSizeBytes(Long sizeBytes) {
    this.sizeBytes = sizeBytes;
  }

  public String getSha256() {
    return sha256;
  }

  public void setSha256(String sha256) {
    this.sha256 = sha256;
  }

  public String getFailureCategory() {
    return failureCategory;
  }

  public void setFailureCategory(String failureCategory) {
    this.failureCategory = failureCategory;
  }

  public String getFailureMessage() {
    return failureMessage;
  }

  public void setFailureMessage(String failureMessage) {
    this.failureMessage = failureMessage;
  }

  public LocalDateTime getStartedAt() {
    return startedAt;
  }

  public void setStartedAt(LocalDateTime startedAt) {
    this.startedAt = startedAt;
  }

  public LocalDateTime getCompletedAt() {
    return completedAt;
  }

  public void setCompletedAt(LocalDateTime completedAt) {
    this.completedAt = completedAt;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(LocalDateTime createdAt) {
    this.createdAt = createdAt;
  }

  public LocalDateTime getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(LocalDateTime updatedAt) {
    this.updatedAt = updatedAt;
  }

  public Integer getVersion() {
    return version;
  }

  public void setVersion(Integer version) {
    this.version = version;
  }
}
