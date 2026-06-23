package ai.cerbur.crag.storage.result;

/**
 * Sparse 检索投影 —— 承载 FTS 全文检索（ts_rank）返回字段.
 *
 * <p>这是 storage DAO 返回给 retrieval 的数据库投影类型，包含构造 ChunkBO 所需的最小字段与 sparseScore.
 *
 * @since 2026-06-17
 */
public class SparseSearchResult {

  private final long chunkId;
  private final long parentChunkId;
  private final Integer chunkIndex;
  private final String content;
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
    this.chunkId = chunkId;
    this.parentChunkId = parentChunkId;
    this.chunkIndex = chunkIndex;
    this.content = content;
    this.sparseScore = sparseScore;
  }

  public long getChunkId() {
    return chunkId;
  }

  public long getParentChunkId() {
    return parentChunkId;
  }

  public Integer getChunkIndex() {
    return chunkIndex;
  }

  public double getSparseScore() {
    return sparseScore;
  }

  public String getContent() {
    return content;
  }
}
