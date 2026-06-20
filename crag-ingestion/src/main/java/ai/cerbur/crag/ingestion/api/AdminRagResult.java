package ai.cerbur.crag.ingestion.api;

import java.util.Collections;
import java.util.List;

/**
 * AdminRag 入库结果 —— 返回 docId、子 chunk 数量、状态和 parent chunk ID 列表.
 *
 * @param docId 文档唯一标识（UUID 字符串）
 * @param chunks 子级 child chunk 数量（不含 parent）
 * @param status 入库状态，"PENDING" 表示 chunk 已写入，Dense + Sparse 索引异步进行中
 * @param parentChunkIds 预生成的 parent chunk ID 列表，不可变；空列表表示无分块输出
 * @since 2026-06-12
 */
public record AdminRagResult(String docId, int chunks, String status, List<String> parentChunkIds) {

  /**
   * 紧凑构造器 —— 防御性复制 parentChunkIds 并拒绝 null.
   *
   * @throws IllegalArgumentException parentChunkIds 为 null
   */
  public AdminRagResult {
    if (parentChunkIds == null) {
      throw new IllegalArgumentException("parentChunkIds must not be null");
    }
    parentChunkIds = List.copyOf(parentChunkIds);
  }

  @Override
  public List<String> parentChunkIds() {
    return Collections.unmodifiableList(parentChunkIds);
  }
}
