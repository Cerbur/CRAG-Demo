package ai.cerbur.crag.storage;

import ai.cerbur.crag.storage.repository.ChunkFtsRepository;
import ai.cerbur.crag.storage.result.SparseSearchResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Chunk FTS DAO —— chunk_fts 表业务数据访问，只依赖 Repository.
 *
 * <p>职责： - 业务判断逻辑（如幂等检查）在此层完成 - CJK 分词和 tsvector 格式转换在 Repository 层 native SQL 完成 - 不使用
 * JdbcTemplate，不直接手写 SQL - 返回窄类型 {@link SparseSearchResult}，仅承载 FTS 阶段的字段
 *
 * @since 2026-06-13
 */
@Component
public class ChunkFtsDao {

  /** SLF4J 日志记录器，用于输出 FTS 写入的 debug 级别日志. */
  private static final Logger log = LoggerFactory.getLogger(ChunkFtsDao.class);

  /** chunk_fts 表 Repository，提供 FTS 全文检索记录的数据库写入与幂等检查. */
  @Autowired private ChunkFtsRepository chunkFtsRepository;

  /**
   * 检查指定 chunk 的 FTS 记录是否已存在.
   *
   * @param chunkId chunk ID
   * @return true 表示已有 FTS 记录
   */
  public boolean existsByChunkId(long chunkId) {
    return chunkFtsRepository.existsByChunkId(chunkId);
  }

  /**
   * 写入 FTS 全文检索记录（幂等）.
   *
   * <p>先通过 existsByChunkId 做幂等检查，已存在则跳过。 CJK 空格正则和 to_tsvector 格式转换在 Repository 层 native SQL 完成。
   * Plan 19 起 {@code knowledgeBaseId} 必填，且必须从可信 chunk 投影派生，保证三表 KB 一致.
   *
   * @param chunkId chunk ID
   * @param knowledgeBaseId 所属知识库 ID（须与对应 chunk 行一致）
   * @param rawContent 原始文本内容
   */
  public void insert(long chunkId, long knowledgeBaseId, String rawContent) {
    if (chunkFtsRepository.existsByChunkId(chunkId)) {
      log.debug("FTS already exists for chunk {}, skipping", chunkId);
      return;
    }
    chunkFtsRepository.insert(chunkId, knowledgeBaseId, rawContent);
    log.debug("FTS inserted — chunkId={} knowledgeBaseId={}", chunkId, knowledgeBaseId);
  }

  /**
   * chunk_fts 表记录总数（供冒烟测试等场景用）.
   *
   * @return chunk_fts 表记录数
   */
  public long count() {
    return chunkFtsRepository.count();
  }

  /**
   * FTS 全文检索查询 —— 委托 Repository 执行 ts_rank 排序检索，映射为 {@link SparseSearchResult}.
   *
   * <p>Repository 采用部分匹配（OR）召回：含疑问词或扩展词、其部分 token 不在目标 chunk 中的 query 仍能命中任一匹配 token 的 chunk，排序为
   * score 降序、chunk_id 升序。匹配语义与 SQL 细节见 {@link ChunkFtsRepository#searchFts}.
   *
   * <p>流程： 1. 空查询保护：query 为 null 或空白时返回空列表 2. 委托 ChunkFtsRepository 执行 native SQL（CJK 预处理在 DB 侧完成）
   * 3. Object[] 列映射为 SparseSearchResult（列索引：0=chunkId, 1=parentChunkId, 2=chunkIndex, 3=score,
   * 4=content）
   *
   * @param query 用户查询文本
   * @param limit 返回数量上限
   * @return 按 ts_rank 降序排列的 SparseSearchResult 列表
   */
  public List<SparseSearchResult> searchFts(long knowledgeBaseId, String query, int limit) {
    if (query == null || query.isBlank()) {
      return Collections.emptyList();
    }

    List<Object[]> rows = chunkFtsRepository.searchFts(knowledgeBaseId, query, limit);

    List<SparseSearchResult> results = new ArrayList<>(rows.size());
    for (Object[] row : rows) {
      long chunkId = ((Number) row[0]).longValue();
      long parentChunkId = ((Number) row[1]).longValue();
      Integer chunkIndex = row[2] == null ? null : ((Number) row[2]).intValue();
      double score = ((Number) row[3]).doubleValue();
      String content = (String) row[4];
      results.add(new SparseSearchResult(chunkId, parentChunkId, chunkIndex, score, content));
    }
    return results;
  }
}
