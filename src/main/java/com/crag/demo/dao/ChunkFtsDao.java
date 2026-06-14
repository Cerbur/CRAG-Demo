package com.crag.demo.dao;

import com.crag.demo.dao.repository.ChunkFtsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Chunk FTS DAO —— chunk_fts 表业务数据访问，只依赖 Repository.
 *
 * 职责：
 * - 业务判断逻辑（如幂等检查）在此层完成
 * - CJK 分词和 tsvector 格式转换在 Repository 层 native SQL 完成
 * - 不使用 JdbcTemplate，不直接手写 SQL
 *
 * @since 2026-06-13
 */
@Component
public class ChunkFtsDao {

    /**
     * SLF4J 日志记录器，用于输出 FTS 写入的 debug 级别日志.
     */
    private static final Logger log = LoggerFactory.getLogger(ChunkFtsDao.class);

    /**
     * chunk_fts 表 Repository，提供 FTS 全文检索记录的数据库写入与幂等检查.
     */
    @Autowired
    private ChunkFtsRepository chunkFtsRepository;

    /**
     * 检查指定 chunk 的 FTS 记录是否已存在.
     *
     * @param chunkId chunk ID
     * @return true 表示已有 FTS 记录
     */
    public boolean existsByChunkId(String chunkId) {
        return chunkFtsRepository.existsByChunkId(chunkId);
    }

    /**
     * 写入 FTS 全文检索记录（幂等）.
     *
     * 先通过 existsByChunkId 做幂等检查，已存在则跳过。
     * CJK 空格正则和 to_tsvector 格式转换在 Repository 层 native SQL 完成。
     *
     * @param chunkId    chunk ID
     * @param rawContent 原始文本内容
     */
    public void insert(String chunkId, String rawContent) {
        if (chunkFtsRepository.existsByChunkId(chunkId)) {
            log.debug("FTS already exists for chunk {}, skipping", chunkId);
            return;
        }
        chunkFtsRepository.insert(chunkId, rawContent);
        log.debug("FTS inserted — chunkId={}", chunkId);
    }

    /**
     * chunk_fts 表记录总数（供冒烟测试等场景用）.
     *
     * @return chunk_fts 表记录数
     */
    public long count() {
        return chunkFtsRepository.count();
    }
}
