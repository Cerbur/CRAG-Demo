package ai.cerbur.crag.storage.result;

/**
 * Dense 检索投影 —— 承载向量相似度检索（余弦距离）返回字段.
 *
 * <p>这是 storage DAO 返回给 retrieval 的数据库投影类型，包含构造 ChunkBO 所需的最小字段与 denseScore.
 *
 * @since 2026-06-17
 */
public class DenseSearchResult {

  private final String chunkId;
  private final String parentChunkId;
  private final Integer chunkIndex;
  private final String content;
  private final double denseScore;

  public DenseSearchResult(
      String chunkId, String parentChunkId, double denseScore, String content) {
    this(chunkId, parentChunkId, null, denseScore, content);
  }

  public DenseSearchResult(
      String chunkId, String parentChunkId, Integer chunkIndex, double denseScore, String content) {
    this.chunkId = chunkId;
    this.parentChunkId = parentChunkId;
    this.chunkIndex = chunkIndex;
    this.content = content;
    this.denseScore = denseScore;
  }

  public String getChunkId() {
    return chunkId;
  }

  public String getParentChunkId() {
    return parentChunkId;
  }

  public Integer getChunkIndex() {
    return chunkIndex;
  }

  public double getDenseScore() {
    return denseScore;
  }

  public String getContent() {
    return content;
  }
}
