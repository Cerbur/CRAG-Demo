package ai.cerbur.crag.storage;

/**
 * 通用检索结果类型 —— Sparse / Dense / RRF 统一使用.
 *
 * 字段含义：
 * - chunkId：child chunk ID；RRF parent 回表后可为 parent chunk ID.
 * - parentChunkId：父 chunk ID，用于回表获取完整 parent 上下文.
 * - score：当前阶段相关性分数（Dense 为余弦距离转换、Sparse 为 ts_rank、RRF 为融合分数）.
 * - content：chunk 文本内容.
 *
 * @since 2026-06-15
 */
public class ChunkSearchResult {

    private final String chunkId;
    private final String parentChunkId;
    private final double score;
    private final String content;

    public ChunkSearchResult(String chunkId, String parentChunkId, double score, String content) {
        this.chunkId = chunkId;
        this.parentChunkId = parentChunkId;
        this.score = score;
        this.content = content;
    }

    public String getChunkId() {
        return chunkId;
    }

    public String getParentChunkId() {
        return parentChunkId;
    }

    public double getScore() {
        return score;
    }

    public String getContent() {
        return content;
    }
}
