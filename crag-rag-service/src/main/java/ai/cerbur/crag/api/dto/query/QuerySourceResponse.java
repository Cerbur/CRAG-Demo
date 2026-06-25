package ai.cerbur.crag.api.dto.query;

import java.util.Collections;
import java.util.List;

/**
 * POST /api/v1/query 成功响应中的来源 DTO —— 对应 QuerySource 的 API 层映射.
 *
 * <p>由 API 层从 {@code QuerySource} 映射，保持相同业务字段，避免直接序列化内部结果类型。matchedChildIds 始终为非 null 列表（可能为空）.
 *
 * @param reference 稳定连续引用编号，如 "S1"
 * @param parentChunkId 对应 ParentEvidenceResult 的 parent 标识
 * @param matchedChildIds 该 parent 在证据窗口中命中的 child chunk ID 列表
 * @since 2026-06-20
 */
public record QuerySourceResponse(
    String reference, String parentChunkId, List<String> matchedChildIds) {

  /** 紧凑构造器 —— 确保 matchedChildIds 永远不为 null. */
  public QuerySourceResponse {
    if (matchedChildIds == null) {
      matchedChildIds = List.of();
    }
  }

  /**
   * 返回不可修改的 matchedChildIds.
   *
   * @return 不可修改的 matched child ID 列表
   */
  @Override
  public List<String> matchedChildIds() {
    return Collections.unmodifiableList(matchedChildIds);
  }
}
