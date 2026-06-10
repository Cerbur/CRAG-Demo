package com.crag.demo.dao.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Chunk 实体 —— 文档分块存储，对应 PostgreSQL chunk 表.
 *
 * Child chunk 为细粒度检索单元（256 token），是唯一会被 Embedding 向量化和参与 FTS 索引的粒度.
 * Parent chunk 为大窗口上下文（1024 token），仅存储纯文本，通过 parent_chunk_id 关联回表获取.
 *
 * @since 2026-06-10
 */
@Entity
@Table(name = "chunk")
public class Chunk {

    /**
     * Chunk 唯一标识，数据库自动生成 UUID.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "chunk_id", nullable = false, updatable = false)
    private UUID chunkId;

    /**
     * 关联文档 ID，标识该 chunk 所属的文档.
     */
    @Column(name = "doc_id", nullable = false)
    private UUID docId;

    /**
     * 父 chunk ID，NULL=parent chunk，非 NULL=child chunk 指向其 parent.
     */
    @Column(name = "parent_chunk_id")
    private UUID parentChunkId;

    /**
     * Child chunk 在 parent chunk 中的序号，从 0 开始递增.
     * Parent chunk 自身此值为 NULL.
     */
    @Column(name = "chunk_index")
    private Integer chunkIndex;

    /**
     * Chunk 文本内容，TEXT 类型，不可为空.
     */
    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    /**
     * Token 数量，用于控制上下文窗口和计费估算.
     */
    @Column(name = "token_count")
    private Integer tokenCount;

    /**
     * 扩展元数据，JSONB 格式，默认 '{}'.
     * 存储标签、来源等自定义字段.
     */
    @Column(name = "metadata", columnDefinition = "JSONB DEFAULT '{}'")
    private String metadata;

    /**
     * Chunk 处理状态: init / processing / success / failed.
     * 仅对 child chunk 有意义，Cron 扫表时只处理 child chunk.
     */
    @Column(name = "status", length = 16)
    private String status;

    /**
     * 记录创建时间，数据库默认 NOW().
     */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /**
     * 记录最后更新时间，数据库默认 NOW().
     */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // --- Getters / Setters ---

    public UUID getChunkId() { return chunkId; }
    public void setChunkId(UUID chunkId) { this.chunkId = chunkId; }

    public UUID getDocId() { return docId; }
    public void setDocId(UUID docId) { this.docId = docId; }

    public UUID getParentChunkId() { return parentChunkId; }
    public void setParentChunkId(UUID parentChunkId) { this.parentChunkId = parentChunkId; }

    public Integer getChunkIndex() { return chunkIndex; }
    public void setChunkIndex(Integer chunkIndex) { this.chunkIndex = chunkIndex; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public Integer getTokenCount() { return tokenCount; }
    public void setTokenCount(Integer tokenCount) { this.tokenCount = tokenCount; }

    public String getMetadata() { return metadata; }
    public void setMetadata(String metadata) { this.metadata = metadata; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
