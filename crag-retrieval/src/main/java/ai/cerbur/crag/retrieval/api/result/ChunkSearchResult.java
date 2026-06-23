package ai.cerbur.crag.retrieval.api.result;

import ai.cerbur.crag.retrieval.bo.ChunkBO;
import ai.cerbur.crag.retrieval.result.RrfFusionResult;

/**
 * 最终检索结果 —— 检索管道最外层的宽类型，组装全部四路得分.
 *
 * <p>检索管道中每一层返回各自职责范围内的窄类型：
 *
 * <pre>
 *   SparseSearchResult  (ChunkBO, sparseScore)
 *   DenseSearchResult   (ChunkBO, denseScore)
 *   RrfFusionResult     (ChunkBO, rrfScore + best sparse/dense)
 *   ChunkSearchResult   (ChunkBO + 全部四路得分)  ← 最外层
 * </pre>
 *
 * 由 Rerank 阶段通过 {@link #fromRrfWithRerank(RrfFusionResult, double)} 组装， 在 {@code RetrievalService}
 * 中作为查询链路对外返回结果，通过组合保留 chunk 业务信息.
 *
 * @since 2026-06-15
 */
public class ChunkSearchResult {

  private final ChunkBO chunk;
  private final Double sparseScore;
  private final Double denseScore;
  private final Double rrfScore;
  private final Double rerankScore;

  private ChunkSearchResult(
      ChunkBO chunk, Double sparseScore, Double denseScore, Double rrfScore, Double rerankScore) {
    this.chunk = chunk;
    this.sparseScore = sparseScore;
    this.denseScore = denseScore;
    this.rrfScore = rrfScore;
    this.rerankScore = rerankScore;
  }

  public static ChunkSearchResult fromRrfWithRerank(RrfFusionResult rrf, double rerankScore) {
    return new ChunkSearchResult(
        rrf.getChunk(),
        rrf.getBestSparseScore(),
        rrf.getBestDenseScore(),
        rrf.getRrfScore(),
        rerankScore);
  }

  public ChunkBO getChunk() {
    return chunk;
  }

  public long getChunkId() {
    return chunk.getChunkId();
  }

  public long getParentChunkId() {
    return chunk.getParentChunkId();
  }

  public Integer getChunkIndex() {
    return chunk.getChunkIndex();
  }

  public String getContent() {
    return chunk.getContent();
  }

  public Double getSparseScore() {
    return sparseScore;
  }

  public Double getDenseScore() {
    return denseScore;
  }

  public Double getRrfScore() {
    return rrfScore;
  }

  public Double getRerankScore() {
    return rerankScore;
  }
}
