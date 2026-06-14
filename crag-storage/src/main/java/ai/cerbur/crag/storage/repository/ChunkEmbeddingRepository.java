package ai.cerbur.crag.storage.repository;

import ai.cerbur.crag.storage.entity.ChunkEmbedding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Chunk Embedding Repository —— chunk_embedding 表数据访问，纯 DB 类型映射.
 *
 * 只包含与数据库列一一对应的查询/写入，不含业务判断逻辑.
 * 业务判断（如幂等检查、格式转换）统一放在 ChunkEmbeddingDao.
 * pgvector 向量写入通过 native SQL 完成（?::vector 类型转换）.
 *
 * @since 2026-06-10
 */
@Repository
public interface ChunkEmbeddingRepository extends JpaRepository<ChunkEmbedding, String> {

    /**
     * 检查指定 chunk 的 embedding 是否已存在.
     *
     * @param chunkId chunk ID
     * @return true 表示已有 embedding 记录
     */
    boolean existsByChunkId(String chunkId);

    /**
     * 写入 embedding 向量 —— native SQL 处理 pgvector 类型转换.
     *
     * ?1::uuid 将 String 转 PostgreSQL UUID 类型.
     * ?2::vector 将 pgvector 数组字面量（如 "[0.1,0.2,...]"）转 vector(768) 类型.
     * 调用方应先通过 existsByChunkId 做幂等检查，极端并发下 DuplicateKeyException 向上传播.
     *
     * @param chunkId      chunk ID
     * @param vectorString pgvector 数组字面量，由 ChunkEmbeddingDao 负责 float[] → String 转换
     */
    @Modifying
    @Transactional
    @Query(value = "INSERT INTO chunk_embedding (chunk_id, embedding) VALUES (CAST(?1 AS uuid), CAST(?2 AS vector))", nativeQuery = true)
    void insert(String chunkId, String vectorString);
}
