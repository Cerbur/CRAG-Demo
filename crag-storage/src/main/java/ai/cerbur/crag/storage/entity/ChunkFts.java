package ai.cerbur.crag.storage.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * Chunk FTS 实体 —— Sparse 全文检索存储，与 chunk 主表解耦.
 *
 * <p>仅存储 child chunk 的 tsvector 分词结果，独立于 chunk 元数据表。 换分词策略时可直接重建，不影响 chunk 主表。 一期通过原生 SQL /
 * JdbcTemplate 操作 tsvector 类型.
 *
 * <p>从 Plan 15 起，chunkId 从 VARCHAR(36) 切换为 BIGINT.
 *
 * @since 2026-06-10
 */
@Entity
@Table(name = "chunk_fts")
public class ChunkFts {

  /** Chunk ID，同时是主键和外键，关联 chunk(chunk_id)，级联删除. */
  @Id
  @Column(name = "chunk_id", nullable = false)
  private Long chunkId;

  /**
   * 全文检索分词内容，tsvector 类型. 通过 to_tsvector('chinese', chunk.content) 生成. 一期通过 JdbcTemplate / Native
   * Query 操作.
   */
  @Column(name = "fts_content", nullable = false, columnDefinition = "tsvector")
  private String ftsContent;

  /** FTS 索引创建时间. */
  @Column(name = "created_at", updatable = false)
  private LocalDateTime createdAt;

  /** 记录最后更新时间，数据库默认 NOW(). */
  @Column(name = "updated_at")
  private LocalDateTime updatedAt;

  /** 乐观锁版本号，手动管理. */
  @Column(name = "version")
  private Integer version;

  // --- Getters / Setters ---

  public Long getChunkId() {
    return chunkId;
  }

  public void setChunkId(Long chunkId) {
    this.chunkId = chunkId;
  }

  public String getFtsContent() {
    return ftsContent;
  }

  public void setFtsContent(String ftsContent) {
    this.ftsContent = ftsContent;
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
