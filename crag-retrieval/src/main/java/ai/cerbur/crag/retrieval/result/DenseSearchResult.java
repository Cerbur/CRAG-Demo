package ai.cerbur.crag.retrieval.result;

import ai.cerbur.crag.retrieval.bo.ChunkBO;

/**
 * Dense 检索结果 —— 承载向量相似度得分与 chunk 业务对象.
 *
 * <p>这是检索管道中最内层的窄类型，组合 {@link ChunkBO} 并只新增 Dense 阶段自己产出的得分. 下游 RRF 融合阶段消费此类型并产出更宽的 {@link
 * RrfFusionResult}.
 *
 * @since 2026-06-17
 */
public class DenseSearchResult {

  private final ChunkBO chunk;
  private final double denseScore;

  public DenseSearchResult(
      String chunkId, String parentChunkId, double denseScore, String content) {
    this(chunkId, parentChunkId, null, denseScore, content);
  }

  public DenseSearchResult(
      String chunkId, String parentChunkId, Integer chunkIndex, double denseScore, String content) {
    this(new ChunkBO(chunkId, parentChunkId, chunkIndex, content), denseScore);
  }

  public DenseSearchResult(ChunkBO chunk, double denseScore) {
    this.chunk = chunk;
    this.denseScore = denseScore;
  }

  public ChunkBO getChunk() {
    return chunk;
  }

  public String getChunkId() {
    return chunk.getChunkId();
  }

  public String getParentChunkId() {
    return chunk.getParentChunkId();
  }

  public Integer getChunkIndex() {
    return chunk.getChunkIndex();
  }

  public double getDenseScore() {
    return denseScore;
  }

  public String getContent() {
    return chunk.getContent();
  }
}
