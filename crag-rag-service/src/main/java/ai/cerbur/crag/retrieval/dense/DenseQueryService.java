package ai.cerbur.crag.retrieval.dense;

import ai.cerbur.crag.retrieval.bo.ChunkBO;
import ai.cerbur.crag.retrieval.result.DenseSearchResult;
import ai.cerbur.crag.storage.ChunkEmbeddingDao;
import java.util.Collections;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Dense 稠密查询服务 —— 基于 pgvector 向量相似度在 child chunk 维度做语义检索.
 *
 * <p>检索流水线并行分支之一，与 SparseQuery 同时发出，结果经 RRF 融合. 返回窄类型 {@link DenseSearchResult}，仅承载 Dense 阶段的得分.
 *
 * @since 2026-06-10
 */
@Component
public class DenseQueryService {

  @Autowired private ChunkEmbeddingDao chunkEmbeddingDao;

  /**
   * 向量相似度检索（限定知识库）.
   *
   * <p>委托 ChunkEmbeddingDao.searchSimilar 执行 pgvector {@code <=>} 余弦相似度查询，先以 {@code
   * knowledgeBaseId} 限定候选再 排序，topK 控制返回数量，空向量时返回空列表.
   *
   * @param knowledgeBaseId 知识库 ID（候选限定）
   * @param queryEmbedding query 向量（768 维）
   * @param topK 返回数量
   * @return 按余弦相似度降序排列的 DenseSearchResult 列表
   */
  public List<DenseSearchResult> search(long knowledgeBaseId, float[] queryEmbedding, int topK) {
    if (queryEmbedding == null || queryEmbedding.length == 0 || topK <= 0) {
      return Collections.emptyList();
    }
    return chunkEmbeddingDao.searchSimilar(knowledgeBaseId, queryEmbedding, topK).stream()
        .map(
            result ->
                new DenseSearchResult(
                    new ChunkBO(
                        result.getChunkId(),
                        result.getParentChunkId(),
                        result.getChunkIndex(),
                        result.getContent()),
                    result.getDenseScore()))
        .toList();
  }
}
