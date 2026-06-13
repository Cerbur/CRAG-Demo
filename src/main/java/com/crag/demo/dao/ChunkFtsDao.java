package com.crag.demo.dao;

import com.crag.demo.dao.repository.ChunkFtsRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Chunk FTS DAO —— chunk_fts 表业务数据访问，只依赖 ChunkFtsRepository.
 *
 * 一期 FTS 全文检索尚未实现，仅提供 count 等基础透传供冒烟测试和监控场景使用.
 *
 * @since 2026-06-13
 */
@Component
public class ChunkFtsDao {

    @Autowired
    private ChunkFtsRepository chunkFtsRepository;

    /**
     * chunk_fts 表记录总数（供冒烟测试等场景用）.
     *
     * @return chunk_fts 表记录数
     */
    public long count() {
        return chunkFtsRepository.count();
    }
}
