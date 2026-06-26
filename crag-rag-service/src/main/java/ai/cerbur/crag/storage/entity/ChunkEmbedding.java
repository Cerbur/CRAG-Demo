package ai.cerbur.crag.storage.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * Chunk Embedding 实体 —— Dense 向量存储，与 chunk 主表解耦.
 *
 * <p>仅存储 child chunk 的 embedding 向量（768 维），独立于 chunk 元数据表。 换模型时可直接 truncate + 重算，不影响 chunk 主表。
 * 一期通过原生 SQL / JdbcTemplate 操作 vector 类型，JPA Entity 仅定义表结构映射.
 *
 * <p>从 Plan 15 起，chunkId 从 VARCHAR(36) 切换为 BIGINT.
 *
 * @since 2026-06-10
 */
@Entity
@Table(name = "chunk_embedding")
public class ChunkEmbedding {

  /** Chunk ID，同时是主键. Plan 19 起不再建立指向 chunk 的数据库外键，应用层保证一致性. */
  @Id
  @Column(name = "chunk_id", nullable = false)
  private Long chunkId;

  /**
   * 所属知识库 ID（Plan 19）. 必须与对应 chunk 行的 {@code knowledge_base_id} 一致；写入只能从 可信 chunk 投影派生，Dense
   * 查询以此先行限定候选.
   */
  @Column(name = "knowledge_base_id", nullable = false)
  private long knowledgeBaseId;

  /**
   * Embedding 向量，768 维（text2vec-base-chinese 输出维度）. 一期通过 JdbcTemplate / Native Query 操作，不依赖 JPA
   * 类型映射. pgvector 列类型: vector(768).
   */
  @Column(name = "embedding", nullable = false, columnDefinition = "vector(768)")
  private String embedding;

  /** 向量生成时间. */
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

  public long getKnowledgeBaseId() {
    return knowledgeBaseId;
  }

  public void setKnowledgeBaseId(long knowledgeBaseId) {
    this.knowledgeBaseId = knowledgeBaseId;
  }

  public String getEmbedding() {
    return embedding;
  }

  public void setEmbedding(String embedding) {
    this.embedding = embedding;
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
