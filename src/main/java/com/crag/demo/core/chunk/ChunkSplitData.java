package com.crag.demo.core.chunk;

/**
 * 单个 ChunkSplit 的数据载体 —— 分块后的文本内容及元信息.
 *
 * 用于 ChunkSplitService 内部分块结果传递，不直接持久化.
 * child 的 chunkIndex 为在 parent 内的序号（0-based），parent 的 chunkIndex 为在文档中的序号（0-based）.
 *
 * @param content     chunk 文本内容
 * @param tokenCount  token 估算数量
 * @param chunkIndex  child：在 parent 内的序号（0-based）；parent：在文档中的序号（0-based）
 * @since 2026-06-12
 */
public record ChunkSplitData(
    String content,
    int tokenCount,
    Integer chunkIndex
) {
}
