package ai.cerbur.crag.storage.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Chunk 实体 —— 文档分块存储，对应 PostgreSQL chunk 表.
 *
 * <p>Child chunk 为细粒度检索单元（256 token），是唯一会被 Embedding 向量化和参与 FTS 索引的粒度. Parent chunk 为大窗口上下文（1024
 * token），仅存储纯文本，通过 parent_chunk_id 关联回表获取.
 *
 * <p>从 Plan 15 起，所有 ID 字段（chunkId、docId、parentChunkId）从 UUID 字符串切换为 Snowflake 生成的
 * {@code long}（数据库端对应 {@code BIGINT}）。ID 由调用方通过 {@code CragIdGenerator} 预生成，实体不再自行生成。
 *
 * @since 2026-06-10
 */
@Entity
@Table(name = "chunk")
public class Chunk {

  /** 哨兵值 —— 表示 parent chunk 无父节点，用 0 代替空字符串/NULL. */
  public static final long NO_PARENT = 0L;

  /**
   * Chunk 唯一标识，由调用方通过 CragIdGenerator 预生成. 不使用 @GeneratedValue：父子关联需预知父 chunkId，因此所有实体 ID
   * 在入库前由业务层统一分配.
   */
  @Id
  @Column(name = "chunk_id", nullable = false, updatable = false)
  private Long chunkId;

  /** 关联文档 ID，标识该 chunk 所属的文档. */
  @Column(name = "doc_id", nullable = false)
  private long docId;

  /** 父 chunk ID. {@link #NO_PARENT} = parent chunk（无父节点），其他值 = child chunk 指向其 parent. */
  @Column(name = "parent_chunk_id", nullable = false)
  private long parentChunkId;

  /** Child chunk 在 parent chunk 中的序号，从 0 开始递增. Parent chunk 自身此值为 NULL. */
  @Column(name = "chunk_index")
  private Integer chunkIndex;

  /** Chunk 文本内容，TEXT 类型，不可为空. */
  @Column(name = "content", nullable = false, columnDefinition = "TEXT")
  private String content;

  /** Token 数量，用于控制上下文窗口和计费估算. */
  @Column(name = "token_count")
  private Integer tokenCount;

  /** 扩展元数据，JSONB 格式，默认 '{}'. 存储标签、来源等自定义字段. */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "metadata", columnDefinition = "JSONB DEFAULT '{}'")
  private String metadata;

  /**
   * Dense 链路状态（Embedding 向量化）. 数据库 SMALLINT: 0=INIT 1=PROCESSING 2=SUCCESS 3=FAILED 4=SKIPPED.
   * Parent chunk 无需 embedding，设为 SKIPPED. Dense Cron 扫表条件: dense_status IN (0,3) AND
   * parent_chunk_id IS NOT NULL.
   */
  @Column(name = "dense_status")
  @Convert(converter = ChunkStatusConverter.class)
  private ChunkStatus denseStatus;

  /**
   * Sparse 链路状态（FTS 全文检索）. 数据库 SMALLINT: 0=INIT 1=PROCESSING 2=SUCCESS 3=FAILED 4=SKIPPED. Parent
   * chunk 无需 FTS 分词，设为 SKIPPED. Sparse Cron 扫表条件: sparse_status IN (0,3) AND parent_chunk_id IS NOT
   * NULL.
   */
  @Column(name = "sparse_status")
  @Convert(converter = ChunkStatusConverter.class)
  private ChunkStatus sparseStatus;

  /** 记录创建时间，数据库默认 NOW(). */
  @Column(name = "created_at", updatable = false)
  private LocalDateTime createdAt;

  /** 记录最后更新时间，数据库默认 NOW(). */
  @Column(name = "updated_at")
  private LocalDateTime updatedAt;

  /** 乐观锁版本号，手动在 JPQL CAS 更新中递增（c.version = c.version + 1）， 不依赖 JPA {@code @Version} 自动管理. */
  @Column(name = "version")
  private Integer version;

  // --- Static factory methods ---

  /**
   * 创建 parent chunk —— dense/sparse 均为 SKIPPED，不做后续向量化/FTS. chunkId 由调用方从 CragIdGenerator 预分配.
   *
   * @param chunkId 预分配的唯一 chunk ID（Snowflake CHUNK 类型）
   * @param docId 文档 ID（Snowflake LEGACY_DOCUMENT 类型）
   * @param content 父级 chunk 文本（~1024 token 大窗口）
   * @param tokenCount token 数
   * @param chunkIndex 在文档中的序号（0-based）
   * @param metadata JSONB 元数据
   * @return parent Chunk 实体
   */
  public static Chunk createParent(
      long chunkId, long docId, String content, int tokenCount, Integer chunkIndex, String metadata) {
    Chunk chunk = new Chunk();
    chunk.setChunkId(chunkId);
    chunk.setDocId(docId);
    chunk.setParentChunkId(NO_PARENT);
    chunk.setChunkIndex(chunkIndex);
    chunk.setContent(content);
    chunk.setTokenCount(tokenCount);
    chunk.setMetadata(metadata);
    chunk.setDenseStatus(ChunkStatus.SKIPPED);
    chunk.setSparseStatus(ChunkStatus.SKIPPED);
    chunk.setVersion(0);
    return chunk;
  }

  /**
   * 创建 child chunk —— dense/sparse 均为 INIT，等待 Cron 异步处理. chunkId 由调用方从 CragIdGenerator 预分配.
   *
   * @param chunkId 预分配的唯一 chunk ID（Snowflake CHUNK 类型）
   * @param docId 文档 ID（Snowflake LEGACY_DOCUMENT 类型）
   * @param parentChunkId 父 chunk ID
   * @param content 子级 chunk 文本（~256 token 细粒度）
   * @param tokenCount token 数
   * @param chunkIndex 在 parent 内的序号（0-based）
   * @param metadata JSONB 元数据
   * @return child Chunk 实体
   */
  public static Chunk createChild(
      long chunkId,
      long docId,
      long parentChunkId,
      String content,
      int tokenCount,
      int chunkIndex,
      String metadata) {
    Chunk chunk = new Chunk();
    chunk.setChunkId(chunkId);
    chunk.setDocId(docId);
    chunk.setParentChunkId(parentChunkId);
    chunk.setChunkIndex(chunkIndex);
    chunk.setContent(content);
    chunk.setTokenCount(tokenCount);
    chunk.setMetadata(metadata);
    chunk.setDenseStatus(ChunkStatus.INIT);
    chunk.setSparseStatus(ChunkStatus.INIT);
    chunk.setVersion(0);
    return chunk;
  }

  // --- Getters / Setters ---

  public Long getChunkId() {
    return chunkId;
  }

  public void setChunkId(Long chunkId) {
    this.chunkId = chunkId;
  }

  public long getDocId() {
    return docId;
  }

  public void setDocId(long docId) {
    this.docId = docId;
  }

  public long getParentChunkId() {
    return parentChunkId;
  }

  public void setParentChunkId(long parentChunkId) {
    this.parentChunkId = parentChunkId;
  }

  public Integer getChunkIndex() {
    return chunkIndex;
  }

  public void setChunkIndex(Integer chunkIndex) {
    this.chunkIndex = chunkIndex;
  }

  public String getContent() {
    return content;
  }

  public void setContent(String content) {
    this.content = content;
  }

  public Integer getTokenCount() {
    return tokenCount;
  }

  public void setTokenCount(Integer tokenCount) {
    this.tokenCount = tokenCount;
  }

  public String getMetadata() {
    return metadata;
  }

  public void setMetadata(String metadata) {
    this.metadata = metadata;
  }

  public ChunkStatus getDenseStatus() {
    return denseStatus;
  }

  public void setDenseStatus(ChunkStatus denseStatus) {
    this.denseStatus = denseStatus;
  }

  public ChunkStatus getSparseStatus() {
    return sparseStatus;
  }

  public void setSparseStatus(ChunkStatus sparseStatus) {
    this.sparseStatus = sparseStatus;
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
