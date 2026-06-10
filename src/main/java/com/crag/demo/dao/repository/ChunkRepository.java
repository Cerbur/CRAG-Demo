package com.crag.demo.dao.repository;

import com.crag.demo.dao.entity.Chunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

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
     * Cron 扫表查询 —— 按状态列表查找待处理的 chunk.
     * 仅 child chunk（parent_chunk_id IS NOT NULL）参与扫表，调用方自行过滤.
     *
     * @param statuses 状态列表，一期为 ['init', 'failed']
     * @return 匹配的 chunk 列表
     */
    List<Chunk> findByStatusIn(List<String> statuses);

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
