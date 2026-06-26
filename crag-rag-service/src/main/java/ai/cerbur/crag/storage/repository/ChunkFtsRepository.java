package ai.cerbur.crag.storage.repository;

import ai.cerbur.crag.storage.entity.ChunkFts;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Chunk FTS Repository —— chunk_fts 表数据访问，纯 DB 类型映射.
 *
 * <p>提供 FTS 全文检索写入（tsvector 分词）和基础幂等检查。 CJK 空格正则和 tsvector 类型转换在 native SQL 侧完成。
 *
 * @since 2026-06-10
 */
@Repository
public interface ChunkFtsRepository extends JpaRepository<ChunkFts, Long> {

  /**
   * 检查指定 chunk 的 FTS 记录是否已存在.
   *
   * @param chunkId chunk ID
   * @return true 表示已有 FTS 记录
   */
  boolean existsByChunkId(long chunkId);

  /**
   * 写入 FTS 全文检索记录 —— native SQL 处理 CJK 分词 + tsvector 类型转换.
   *
   * <p>regexp_replace(?2, '([一-龥])', '\\1 ', 'g') 在每个 CJK 汉字后插入空格，使每个字成为独立 token。
   * to_tsvector('simple', ...) 按空格分词，不区分大小写。
   *
   * @param chunkId chunk ID
   * @param rawContent 原始文本内容（未经 CJK 预处理）
   */
  @Modifying
  @Transactional
  @Query(
      value =
          """
        INSERT INTO chunk_fts (chunk_id, knowledge_base_id, fts_content)
         VALUES (?1, ?2,
                to_tsvector('simple',
                    regexp_replace(?3, '([一-龥])', '\\1 ', 'g')))
        """,
      nativeQuery = true)
  void insert(long chunkId, long knowledgeBaseId, String rawContent);

  /**
   * FTS 全文检索查询 —— 使用 ts_rank 排序，JOIN chunk 表获取 child content 和 parent chunk ID.
   *
   * <p>查询侧 CJK 预处理与写入侧保持一致：每个 CJK 字符后插入空格，使单字作为 token 参与匹配.
   *
   * <p>匹配语义为部分匹配（OR）：查询 tsquery 由 {@code to_tsvector('simple', CJK 预处理后的 query)} 拆出的每个 lexeme 以
   * {@code |} 组合而成，只要 chunk 命中任一 lexeme 即召回。相比 {@code plainto_tsquery} 的 AND 语义 （要求 query 全部 token
   * 都出现在 chunk 中），含疑问词或扩展词、其部分 token 不在目标 chunk 中的 query 仍能命中。 查询与写入复用同一个 {@code
   * to_tsvector('simple', ...)} 分词函数，保证 query 与 document 的 token 语义一致。
   *
   * <p>空或无 token 的 query 经 {@code COALESCE} 回退到空 tsquery，{@code @@} 不匹配任何行，不会全表扫描；正常空白 query 已在 DAO
   * 层 {@code isBlank} 守卫拦截，不会到达此 SQL。原始 query 以绑定参数 {@code :query} 传入，不拼接进 SQL。分数为
   * ts_rank（归一化排名），排序为 score 降序、{@code chunk_id} 升序 tie-breaker。
   *
   * <p>返回列顺序：[chunk_id, parent_chunk_id, chunk_index, score, content]，由 ChunkFtsDao 负责映射.
   *
   * @param query 用户查询文本
   * @param limit 返回数量上限
   * @return 原始列结果列表，每行为 [chunk_id, parent_chunk_id, chunk_index, score, content]
   */
  @Query(
      value =
          """
        SELECT c.chunk_id,
               c.parent_chunk_id,
               c.chunk_index,
               ts_rank(cf.fts_content, qt.tsq) AS score,
               c.content
          FROM chunk_fts cf
          JOIN chunk c ON c.chunk_id = cf.chunk_id
         CROSS JOIN LATERAL (
               SELECT COALESCE(
                        to_tsquery('simple', string_agg(lexeme, ' | ')),
                        ''::tsquery
                      ) AS tsq
                 FROM unnest(to_tsvector('simple',
                            regexp_replace(:query, '([一-龥])', '\\1 ', 'g')))
              ) qt
         WHERE cf.knowledge_base_id = :knowledgeBaseId AND cf.fts_content @@ qt.tsq
         ORDER BY score DESC, c.chunk_id ASC
         LIMIT :limit
        """,
      nativeQuery = true)
  List<Object[]> searchFts(
      @Param("knowledgeBaseId") long knowledgeBaseId,
      @Param("query") String query,
      @Param("limit") int limit);
}
