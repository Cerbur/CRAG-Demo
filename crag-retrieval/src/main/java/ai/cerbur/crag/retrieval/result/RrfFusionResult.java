package ai.cerbur.crag.retrieval.result;

import ai.cerbur.crag.retrieval.bo.ChunkBO;

/**
 * RRF 融合结果 —— 承载 RRF 融合阶段得分与 chunk 业务对象.
 *
 * <p>位于检索管道中间层：消费 {@link SparseSearchResult} + {@link DenseSearchResult}， 产出包含 RRF 融合分数及最佳 child
 * 原始得分的窄类型. 下游 Rerank 阶段消费此类型并组装为最终的宽类型 {@link ChunkSearchResult}.
 *
 * @since 2026-06-17
 */
public class RrfFusionResult {

  private final ChunkBO chunk;
  private final double rrfScore;
  private final Double bestSparseScore;
  private final Double bestDenseScore;

  public RrfFusionResult(
      long chunkId,
      double rrfScore,
      String content,
      Double bestSparseScore,
      Double bestDenseScore) {
    this(chunkId, 0L, null, rrfScore, content, bestSparseScore, bestDenseScore);
  }

  public RrfFusionResult(
      long chunkId,
      long parentChunkId,
      Integer chunkIndex,
      double rrfScore,
      String content,
      Double bestSparseScore,
      Double bestDenseScore) {
    this(
        new ChunkBO(chunkId, parentChunkId, chunkIndex, content),
        rrfScore,
        bestSparseScore,
        bestDenseScore);
  }

  public RrfFusionResult(
      ChunkBO chunk, double rrfScore, Double bestSparseScore, Double bestDenseScore) {
    this.chunk = chunk;
    this.rrfScore = rrfScore;
    this.bestSparseScore = bestSparseScore;
    this.bestDenseScore = bestDenseScore;
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

  public double getRrfScore() {
    return rrfScore;
  }

  public String getContent() {
    return chunk.getContent();
  }

  public Double getBestSparseScore() {
    return bestSparseScore;
  }

  public Double getBestDenseScore() {
    return bestDenseScore;
  }
}
