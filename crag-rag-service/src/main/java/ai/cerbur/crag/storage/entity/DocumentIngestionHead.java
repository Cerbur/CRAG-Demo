package ai.cerbur.crag.storage.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * Document ingestion head 实体（Plan 21.4）—— 按 docId 维护当前 operationVersion 的单调指针.
 *
 * <p>每个 docId 恰好一行，{@code operationVersion} 单调递增（由 {@code IngestionHeadService.advance} CAS
 * 推进）。召回路径 以 {@code (knowledgeBaseId, docId) → head.operationVersion} 联合 READY {@code
 * ingestion_job} 限定，保证只有当前 operationVersion 的 READY 索引参与检索；旧版本或未 READY 的索引零召回.
 *
 * <p>迟到 Worker 在 markReady 前必须校验 head.operationVersion 等于自己的 operationVersion，否则视为已被取代，拒绝 READY（由
 * {@code IngestionJobDao.tryMarkReadyWithHead} 在 SQL 层强制）.
 *
 * @since 2026-06-28
 */
@Entity
@Table(name = "document_ingestion_head")
public class DocumentIngestionHead {

  /** 所属知识库 ID. */
  @Column(name = "knowledge_base_id", nullable = false)
  private long knowledgeBaseId;

  /** 文档 ID，主键；每个 docId 恰好一行 head. */
  @Id
  @Column(name = "doc_id", nullable = false, updatable = false)
  private Long docId;

  /** 当前 operationVersion，单调递增. */
  @Column(name = "operation_version", nullable = false)
  private long operationVersion;

  /** 乐观锁版本号，CAS 推进时比对并递增. */
  @Column(name = "version")
  private Integer version;

  /** 最后更新时间. */
  @Column(name = "updated_at")
  private LocalDateTime updatedAt;

  public long getKnowledgeBaseId() {
    return knowledgeBaseId;
  }

  public void setKnowledgeBaseId(long knowledgeBaseId) {
    this.knowledgeBaseId = knowledgeBaseId;
  }

  public Long getDocId() {
    return docId;
  }

  public void setDocId(Long docId) {
    this.docId = docId;
  }

  public long getOperationVersion() {
    return operationVersion;
  }

  public void setOperationVersion(long operationVersion) {
    this.operationVersion = operationVersion;
  }

  public Integer getVersion() {
    return version;
  }

  public void setVersion(Integer version) {
    this.version = version;
  }

  public LocalDateTime getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(LocalDateTime updatedAt) {
    this.updatedAt = updatedAt;
  }
}
