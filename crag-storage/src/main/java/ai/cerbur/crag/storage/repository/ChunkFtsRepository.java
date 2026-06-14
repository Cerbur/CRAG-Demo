package ai.cerbur.crag.storage.repository;

import ai.cerbur.crag.storage.entity.ChunkFts;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Chunk FTS Repository —— chunk_fts 表数据访问，纯 DB 类型映射.
 *
 * 提供 FTS 全文检索写入（tsvector 分词）和基础幂等检查。
 * CJK 空格正则和 tsvector 类型转换在 native SQL 侧完成。
 *
 * @since 2026-06-10
 */
@Repository
public interface ChunkFtsRepository extends JpaRepository<ChunkFts, String> {

    /**
     * 检查指定 chunk 的 FTS 记录是否已存在.
     *
     * @param chunkId chunk ID
     * @return true 表示已有 FTS 记录
     */
    boolean existsByChunkId(String chunkId);

    /**
     * 写入 FTS 全文检索记录 —— native SQL 处理 CJK 分词 + tsvector 类型转换.
     *
     * regexp_replace(?2, '([一-龥])', '\\1 ', 'g') 在每个 CJK 汉字后插入空格，使每个字成为独立 token。
     * to_tsvector('simple', ...) 按空格分词，不区分大小写。
     *
     * @param chunkId    chunk ID
     * @param rawContent 原始文本内容（未经 CJK 预处理）
     */
    @Modifying
    @Transactional
    @Query(value = """
        INSERT INTO chunk_fts (chunk_id, fts_content)
         VALUES (?1,
                to_tsvector('simple',
                    regexp_replace(?2, '([一-龥])', '\\1 ', 'g')))
        """, nativeQuery = true)
    void insert(String chunkId, String rawContent);
}
