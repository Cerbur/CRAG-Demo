package com.crag.demo.core.chunk;

/**
 * 单个 Chunk 的数据载体 —— 分块后的文本内容及元信息.
 *
 * 用于 ChunkService 内部分块结果传递，不直接持久化.
 * child chunk 的 chunkIndex 从 0 开始递增，parent chunk 的 chunkIndex 为 null.
 *
 * @param content     chunk 文本内容
 * @param tokenCount  token 估算数量
 * @param chunkIndex  child 在 parent 中的序号（0-based），parent chunk 为 null
 * @since 2026-06-12
 */
public record ChunkData(
    String content,
    int tokenCount,
    Integer chunkIndex
) {
}
