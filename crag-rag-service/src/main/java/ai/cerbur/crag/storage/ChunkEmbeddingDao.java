package ai.cerbur.crag.storage;

import ai.cerbur.crag.storage.repository.ChunkEmbeddingRepository;
import ai.cerbur.crag.storage.result.DenseSearchResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * Chunk Embedding DAO —— chunk_embedding 表业务数据访问，只依赖 Repository.
 *
 * <p>职责： - 业务判断逻辑（如幂等检查）在此层完成 - float[] → pgvector 字面量格式转换在此层完成（format 选择是业务判断） - 底层 SQL 全部委托给
 * ChunkEmbeddingRepository（纯 DB 类型映射） - 不使用 JdbcTemplate，不直接手写 SQL - 返回窄类型 {@link
 * DenseSearchResult}，仅承载 Dense 阶段的字段
 *
 * @since 2026-06-13
 */
@Component
public class ChunkEmbeddingDao {

  private static final Logger log = LoggerFactory.getLogger(ChunkEmbeddingDao.class);

  @Autowired private ChunkEmbeddingRepository chunkEmbeddingRepository;

  /**
   * 检查指定 chunk 的 embedding 是否已存在.
   *
   * @param chunkId chunk ID
   * @return true 表示已有 embedding 记录
   */
  public boolean existsByChunkId(long chunkId) {
    return chunkEmbeddingRepository.existsByChunkId(chunkId);
  }

  /**
   * 写入 embedding（纯 INSERT，不做 upsert）.
   *
   * <p>调用方应先通过 {@link #existsByChunkId(long)} 做幂等检查. 极端并发下可能抛出 DuplicateKeyException，由调用方决定重试策略.
   * Plan 19 起 {@code knowledgeBaseId} 必填，且必须从可信 chunk 投影派生，保证 {@code chunk}、{@code chunk_embedding}
   * 与 {@code chunk_fts} 三表 KB 一致.
   *
   * @param chunkId chunk ID
   * @param knowledgeBaseId 所属知识库 ID（须与对应 chunk 行一致）
   * @param vector 768 维稠密向量
   * @throws DuplicateKeyException 同一 chunkId 已被其他实例写入
   */
  public void insert(long chunkId, long knowledgeBaseId, float[] vector) {
    chunkEmbeddingRepository.insert(chunkId, knowledgeBaseId, toPgVectorString(vector));
    log.debug("Embedding inserted — chunkId={} knowledgeBaseId={}", chunkId, knowledgeBaseId);
  }

  /**
   * chunk_embedding 表记录总数（供冒烟测试等场景用）.
   *
   * @return chunk_embedding 表记录数
   */
  public long count() {
    return chunkEmbeddingRepository.count();
  }

  /**
   * 向量相似度检索 —— 基于 pgvector 余弦距离查询 top-k 相似 child chunk，映射为 {@link DenseSearchResult}.
   *
   * <p>流程： 1. 空向量保护：vector 为 null 或长度为 0 时返回空列表 2. float[] → pgvector 数组字面量转换（复用 {@link
   * #toPgVectorString}） 3. 委托 ChunkEmbeddingRepository 执行 native SQL 查询 4. Object[] 列映射为
   * DenseSearchResult（列索引：0=chunkId, 1=parentChunkId, 2=chunkIndex, 3=score, 4=content）
   *
   * @param vector query embedding 向量（768 维）
   * @param limit 返回数量上限
   * @return 按相似度降序排列的 DenseSearchResult 列表
   */
  public List<DenseSearchResult> searchSimilar(float[] vector, int limit) {
    if (vector == null || vector.length == 0) {
      return Collections.emptyList();
    }

    String vectorLiteral = toPgVectorString(vector);
    List<Object[]> rows = chunkEmbeddingRepository.searchSimilar(vectorLiteral, limit);

    List<DenseSearchResult> results = new ArrayList<>(rows.size());
    for (Object[] row : rows) {
      long chunkId = ((Number) row[0]).longValue();
      long parentChunkId = ((Number) row[1]).longValue();
      Integer chunkIndex = row[2] == null ? null : ((Number) row[2]).intValue();
      double score = ((Number) row[3]).doubleValue();
      String content = (String) row[4];
      results.add(new DenseSearchResult(chunkId, parentChunkId, chunkIndex, score, content));
    }
    return results;
  }

  /**
   * 将 float[] 转为 pgvector 兼容的数组字面量.
   *
   * <p>格式选择是业务判断（紧凑格式 vs 空格分隔），不在 Repository 层处理.
   *
   * @param vector 768 维稠密向量
   * @return pgvector 兼容的字符串表示，如 "[0.1,0.2,0.3,...]"
   */
  private String toPgVectorString(float[] vector) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < vector.length; i++) {
      if (i > 0) {
        sb.append(",");
      }
      sb.append(vector[i]);
    }
    sb.append("]");
    return sb.toString();
  }
}
