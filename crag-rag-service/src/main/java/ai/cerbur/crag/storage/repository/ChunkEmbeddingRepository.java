package ai.cerbur.crag.storage.repository;

import ai.cerbur.crag.storage.entity.ChunkEmbedding;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Chunk Embedding Repository —— chunk_embedding 表数据访问，纯 DB 类型映射.
 *
 * <p>只包含与数据库列一一对应的查询/写入，不含业务判断逻辑. 业务判断（如幂等检查、格式转换）统一放在 ChunkEmbeddingDao. pgvector 向量写入通过 native
 * SQL 完成（?::vector 类型转换）.
 *
 * @since 2026-06-10
 */
@Repository
public interface ChunkEmbeddingRepository extends JpaRepository<ChunkEmbedding, Long> {

  /**
   * 检查指定 chunk 的 embedding 是否已存在.
   *
   * @param chunkId chunk ID
   * @return true 表示已有 embedding 记录
   */
  boolean existsByChunkId(long chunkId);

  /**
   * 写入 embedding 向量 —— native SQL 处理 pgvector 类型转换.
   *
   * <p>chunk_id 为 BIGINT，直接传入 long 即可. ?3::vector 将 pgvector 数组字面量（如 "[0.1,0.2,...]"）转 vector(768)
   * 类型. 调用方应先通过 existsByChunkId 做幂等检查，极端并发下 DuplicateKeyException 向上传播. Plan 19 起 {@code
   * knowledge_base_id} 必填，且只能从可信 chunk 投影派生，禁止调用方自行拼装不一致数据.
   *
   * @param chunkId chunk ID
   * @param knowledgeBaseId 所属知识库 ID（须与 chunk 行一致）
   * @param vectorString pgvector 数组字面量，由 ChunkEmbeddingDao 负责 float[] → String 转换
   */
  @Modifying
  @Transactional
  @Query(
      value =
          "INSERT INTO chunk_embedding (chunk_id, knowledge_base_id, embedding)"
              + " VALUES (?1, ?2, CAST(?3 AS vector))",
      nativeQuery = true)
  void insert(long chunkId, long knowledgeBaseId, String vectorString);

  /**
   * 向量相似度检索 —— 使用 pgvector {@code <=>} 余弦距离排序，JOIN chunk 表获取 child content 和 parent chunk ID.
   *
   * <p>返回列顺序：[chunk_id, parent_chunk_id, chunk_index, score, content]，由 ChunkEmbeddingDao 负责映射.
   * {@code CAST(?1 AS vector)} 将 pgvector 数组字面量（如 "[0.1,0.2,...]"）转为向量类型. 分数 = 1 - 余弦距离，值域 [0,
   * 2]，越大越相似.
   *
   * @param vectorLiteral pgvector 数组字面量，由 ChunkEmbeddingDao 做 float[] → String 转换
   * @param limit 返回数量上限
   * @return 原始列结果列表，每行为 [chunk_id, parent_chunk_id, chunk_index, score, content]
   */
  @Query(
      value =
          """
        SELECT c.chunk_id,
               c.parent_chunk_id,
               c.chunk_index,
               1 - (ce.embedding <=> CAST(:vectorLiteral AS vector)) AS score,
               c.content
          FROM chunk_embedding ce
          JOIN chunk c ON c.chunk_id = ce.chunk_id
         ORDER BY ce.embedding <=> CAST(:vectorLiteral AS vector) ASC, c.chunk_id ASC
         LIMIT :limit
        """,
      nativeQuery = true)
  List<Object[]> searchSimilar(
      @Param("vectorLiteral") String vectorLiteral, @Param("limit") int limit);
}
