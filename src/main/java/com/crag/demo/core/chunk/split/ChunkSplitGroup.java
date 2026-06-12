package com.crag.demo.core.chunk.split;

import java.util.List;

/**
 * 一个 parent chunk 及其下属 child chunks.
 *
 * @param parentChunk parent 大窗口上下文
 * @param childChunks parent 内部切出的 child chunks
 * @since 2026-06-12
 */
public record ChunkSplitGroup(
    ChunkSplitData parentChunk,
    List<ChunkSplitData> childChunks
) {

    public ChunkSplitGroup {
        childChunks = List.copyOf(childChunks);
    }
}
