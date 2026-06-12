package com.crag.demo.dao.repository;

import com.crag.demo.dao.entity.ChunkEmbedding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Chunk Embedding Repository —— chunk_embedding 表数据访问.
 *
 * 一期通过 JdbcTemplate / Native Query 操作向量（向量写入、相似度查询）。
 * 此接口提供基础 CRUD 骨架，向量操作留到 plan_2.
 *
 * @since 2026-06-10
 */
@Repository
public interface ChunkEmbeddingRepository extends JpaRepository<ChunkEmbedding, String> {
}
