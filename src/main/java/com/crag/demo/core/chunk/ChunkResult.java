package com.crag.demo.core.chunk;

import java.util.List;

/**
 * 文档分块结果 —— 包含一个 parent chunk 和若干 child chunk.
 *
 * Parent chunk 为 1024 token 大窗口上下文，不参与向量化.
 * Child chunk 为 256 token 细粒度检索单元，是唯一会被 Embedding 向量化和参与检索的粒度.
 *
 * @param parentChunk 父级大块（1024 token 窗口），chunkIndex 为 null
 * @param childChunks 子级小块列表（每个 256 token，含 overlap），按原始顺序排列
 * @since 2026-06-12
 */
public record ChunkResult(
    ChunkData parentChunk,
    List<ChunkData> childChunks
) {
}
