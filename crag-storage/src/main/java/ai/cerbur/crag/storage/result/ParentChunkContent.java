package ai.cerbur.crag.storage.result;

/**
 * Parent chunk 内容投影 —— 最窄的 parent 批量回表结果.
 *
 * <p>仅包含 {@code chunkId} 和 {@code content}，供 Retrieval 模块按 parent 排名组装 Evidence. 不使用 Spring Data
 * 投影接口以避免 Entity 跨模块传播.
 *
 * @since 2026-06-20
 */
public record ParentChunkContent(long chunkId, String content) {

  public ParentChunkContent {
    if (chunkId == 0L) {
      throw new IllegalArgumentException("chunkId must not be 0");
    }
  }
}
