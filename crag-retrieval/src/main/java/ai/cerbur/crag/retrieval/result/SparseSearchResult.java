package ai.cerbur.crag.retrieval.result;

import ai.cerbur.crag.retrieval.bo.ChunkBO;

/**
 * Sparse 检索结果 —— 承载 FTS 全文检索得分与 chunk 业务对象.
 *
 * <p>这是检索管道中最内层的窄类型，组合 {@link ChunkBO} 并只新增 Sparse 阶段自己产出的得分. 下游 RRF 融合阶段消费此类型并产出更宽的 {@link
 * RrfFusionResult}.
 *
 * @since 2026-06-17
 */
public class SparseSearchResult {

  private final ChunkBO chunk;
  private final double sparseScore;

  public SparseSearchResult(
      long chunkId, long parentChunkId, double sparseScore, String content) {
    this(chunkId, parentChunkId, null, sparseScore, content);
  }

  public SparseSearchResult(
      long chunkId,
      long parentChunkId,
      Integer chunkIndex,
      double sparseScore,
      String content) {
    this(new ChunkBO(chunkId, parentChunkId, chunkIndex, content), sparseScore);
  }

  public SparseSearchResult(ChunkBO chunk, double sparseScore) {
    this.chunk = chunk;
    this.sparseScore = sparseScore;
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

  public double getSparseScore() {
    return sparseScore;
  }

  public String getContent() {
    return chunk.getContent();
  }
}
