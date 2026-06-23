package ai.cerbur.crag.retrieval.api.result;

import java.util.Collections;
import java.util.List;

/**
 * Parent evidence 结果 —— Query 链路可消费的不可变 parent chunk 证据.
 *
 * <p>携带完整 parent 内容与在该 Evidence 候选窗口内真实 RRF 命中的 child chunk ID 列表. 不包含 Sparse、Dense、RRF 或 Rerank
 * 分数，也不暴露 Entity 或 DAO 类型.
 *
 * <p>使用 Java {@code record} 保证不可变性；紧凑构造器防御性复制集合并拒绝非法状态.
 *
 * @since 2026-06-20
 */
public record ParentEvidenceResult(long parentChunkId, String content, List<Long> matchedChildIds) {

  /**
   * 紧凑构造器 —— 防御性复制 matchedChildIds 并拒绝非法状态.
   *
   * @throws IllegalArgumentException parentChunkId 为 0
   * @throws IllegalArgumentException content 为 null 或 blank
   * @throws IllegalArgumentException matchedChildIds 为 null 或空集合
   */
  public ParentEvidenceResult {
    if (parentChunkId == 0L) {
      throw new IllegalArgumentException("parentChunkId must not be 0");
    }
    if (content == null || content.isBlank()) {
      throw new IllegalArgumentException("content must not be null or blank");
    }
    if (matchedChildIds == null || matchedChildIds.isEmpty()) {
      throw new IllegalArgumentException("matchedChildIds must not be null or empty");
    }
    matchedChildIds = List.copyOf(matchedChildIds);
  }

  /**
   * 返回不可修改的 matchedChildIds.
   *
   * @return 不可修改的 matched child ID 列表
   */
  @Override
  public List<Long> matchedChildIds() {
    return Collections.unmodifiableList(matchedChildIds);
  }
}
