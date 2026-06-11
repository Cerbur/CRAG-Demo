package com.crag.demo.dao.repository;

import com.crag.demo.dao.entity.Chunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.crag.demo.dao.entity.ChunkStatus;
import java.util.List;
import java.util.UUID;

/**
 * Chunk Repository —— 基于 Spring Data JPA 的 chunk 表数据访问.
 *
 * 提供 Cron 扫表、按文档查询、按 parent 查询 children 三个基础查询方法.
 *
 * @since 2026-06-10
 */
@Repository
public interface ChunkRepository extends JpaRepository<Chunk, UUID> {

    /**
     * Dense Cron 扫表 —— 按 dense_status 查找待处理的 child chunk.
     * 调用方需额外过滤 parent_chunk_id IS NOT NULL.
     *
     * @param statuses 状态列表，一期为 ['init', 'failed']
     * @return 匹配的 chunk 列表
     */
    List<Chunk> findByDenseStatusIn(List<ChunkStatus> statuses);

    /**
     * Sparse Cron 扫表 —— 按 sparse_status 查找待处理的 child chunk.
     * 调用方需额外过滤 parent_chunk_id IS NOT NULL.
     *
     * @param statuses 状态列表，一期为 [INIT, FAILED]
     * @return 匹配的 chunk 列表
     */
    List<Chunk> findBySparseStatusIn(List<ChunkStatus> statuses);

    /**
     * 按文档 ID 查询所有 chunk（parent + child）.
     *
     * @param docId 文档 ID
     * @return 该文档下的所有 chunk
     */
    List<Chunk> findByDocId(UUID docId);

    /**
     * 按 parent chunk ID 查询其下所有 child chunk.
     *
     * @param parentChunkId parent chunk ID
     * @return 该 parent 下的所有 child chunk
     */
    List<Chunk> findByParentChunkId(UUID parentChunkId);
}
