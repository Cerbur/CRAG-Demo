package ai.cerbur.crag.ingestion.chunk.split;

import java.util.ArrayList;
import java.util.List;

/**
 * 文档分块结果 —— 包含若干 parent group，每个 group 下有若干 child chunk.
 *
 * <p>Parent chunk 为 1024 token 大窗口上下文，不参与向量化. Child chunk 为 256 token 细粒度检索单元，是唯一会被 Embedding
 * 向量化和参与检索的粒度.
 *
 * @param chunkGroups parent-child 分组，按原文顺序排列
 * @since 2026-06-12
 */
public record ChunkSplitResult(List<ChunkSplitGroup> chunkGroups) {

  public ChunkSplitResult {
    chunkGroups = List.copyOf(chunkGroups);
  }

  public ChunkSplitResult(ChunkSplitData parentChunk, List<ChunkSplitData> childChunks) {
    this(List.of(new ChunkSplitGroup(parentChunk, childChunks)));
  }

  /** 返回第一个 parent chunk，兼容单 parent 调用场景. */
  public ChunkSplitData parentChunk() {
    return chunkGroups.get(0).parentChunk();
  }

  /** 返回全部 child chunks 的扁平列表，按原文顺序排列. */
  public List<ChunkSplitData> childChunks() {
    List<ChunkSplitData> children = new ArrayList<>();
    for (ChunkSplitGroup group : chunkGroups) {
      children.addAll(group.childChunks());
    }
    return List.copyOf(children);
  }
}
