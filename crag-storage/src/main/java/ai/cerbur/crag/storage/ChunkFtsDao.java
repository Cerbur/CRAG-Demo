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
   *
   * @param chunkId chunk ID
   * @param rawContent 原始文本内容
   */
  public void insert(long chunkId, String rawContent) {
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

  /**
   * FTS 全文检索查询 —— 委托 Repository 执行 ts_rank 排序检索，映射为 {@link SparseSearchResult}.
   *
   * <p>流程： 1. 空查询保护：query 为 null 或空白时返回空列表 2. 委托 ChunkFtsRepository 执行 native SQL（CJK 预处理在 DB 侧完成）
   * 3. Object[] 列映射为 SparseSearchResult（列索引：0=chunkId, 1=parentChunkId, 2=chunkIndex, 3=score,
   * 4=content）
   *
   * @param query 用户查询文本
   * @param limit 返回数量上限
   * @return 按 ts_rank 降序排列的 SparseSearchResult 列表
   */
  public List<SparseSearchResult> searchFts(String query, int limit) {
    if (query == null || query.isBlank()) {
      return Collections.emptyList();
    }

    List<Object[]> rows = chunkFtsRepository.searchFts(query, limit);

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
