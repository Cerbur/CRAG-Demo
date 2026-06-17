package ai.cerbur.crag.retrieval.bo;

/**
 * 检索业务 chunk 对象 —— 承载查询链路需要的 chunk 原始字段.
 *
 * Retrieval 业务链路使用该 BO 传递 chunkId、parentChunkId、chunkIndex 和 content，
 * 避免直接把 JPA Entity 透传到查询编排、RRF、Rerank 等阶段。
 *
 * @since 2026-06-17
 */
public class ChunkBO {

    private final String chunkId;
    private final String parentChunkId;
    private final Integer chunkIndex;
    private final String content;

    public ChunkBO(String chunkId, String parentChunkId, Integer chunkIndex, String content) {
        this.chunkId = chunkId;
        this.parentChunkId = parentChunkId;
        this.chunkIndex = chunkIndex;
        this.content = content;
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

    public String getContent() {
        return content;
    }
}
