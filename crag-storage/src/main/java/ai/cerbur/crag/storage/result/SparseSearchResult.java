package ai.cerbur.crag.storage.result;

/**
 * Sparse 检索投影 —— 承载 FTS 全文检索（ts_rank）返回字段.
 *
 * 这是 storage DAO 返回给 retrieval 的数据库投影类型，包含构造 ChunkBO 所需的最小字段与 sparseScore.
 *
 * @since 2026-06-17
 */
public class SparseSearchResult {

    private final String chunkId;
    private final String parentChunkId;
    private final Integer chunkIndex;
    private final String content;
    private final double sparseScore;

    public SparseSearchResult(String chunkId, String parentChunkId, double sparseScore, String content) {
        this(chunkId, parentChunkId, null, sparseScore, content);
    }

    public SparseSearchResult(String chunkId, String parentChunkId, Integer chunkIndex,
                              double sparseScore, String content) {
        this.chunkId = chunkId;
        this.parentChunkId = parentChunkId;
        this.chunkIndex = chunkIndex;
        this.content = content;
        this.sparseScore = sparseScore;
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

    public double getSparseScore() {
        return sparseScore;
    }

    public String getContent() {
        return content;
    }
}
