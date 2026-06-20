package ai.cerbur.crag.query.api;

import java.util.Collections;
import java.util.List;

/**
 * Query 链路 source 映射 —— 将 included parent evidence 映射为连续编号引用.
 *
 * <p>{@code reference} 格式为 "S1", "S2", ... 对应 Context 中的边界编号与原 Prompt 中的 {@code [Sx]} 引用. {@code
 * matchedChildIds} 保持 Retrieval 返回的原始顺序，不可更改.
 *
 * @param reference 稳定连续引用编号，如 "S1"
 * @param parentChunkId 对应 ParentEvidenceResult 的 parent 标识
 * @param matchedChildIds 该 parent 在证据窗口中命中的 child chunk ID 列表，按 Retrieval 顺序
 */
public record QuerySource(String reference, String parentChunkId, List<String> matchedChildIds) {

  /**
   * 紧凑构造器 —— 防御性复制并拒绝非法状态.
   *
   * @throws IllegalArgumentException reference 为 null 或 blank
   * @throws IllegalArgumentException parentChunkId 为 null 或 blank
   * @throws IllegalArgumentException matchedChildIds 为 null
   */
  public QuerySource {
    if (reference == null || reference.isBlank()) {
      throw new IllegalArgumentException("reference must not be null or blank");
    }
    if (parentChunkId == null || parentChunkId.isBlank()) {
      throw new IllegalArgumentException("parentChunkId must not be null or blank");
    }
    if (matchedChildIds == null) {
      throw new IllegalArgumentException("matchedChildIds must not be null");
    }
    matchedChildIds = List.copyOf(matchedChildIds);
  }

  /**
   * 返回不可修改的 matchedChildIds.
   *
   * @return 不可修改的 matched child ID 列表，保持 Retrieval 顺序
   */
  @Override
  public List<String> matchedChildIds() {
    return Collections.unmodifiableList(matchedChildIds);
  }
}
