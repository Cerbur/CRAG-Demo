package com.crag.demo.dao.repository;

import com.crag.demo.dao.entity.ChunkFts;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Chunk FTS Repository —— chunk_fts 表数据访问.
 *
 * 一期通过 JdbcTemplate / Native Query 操作 tsvector（写入、全文检索）。
 * 此接口提供基础 CRUD 骨架，FTS 查询留到 plan_2.
 *
 * @since 2026-06-10
 */
@Repository
public interface ChunkFtsRepository extends JpaRepository<ChunkFts, String> {
}
