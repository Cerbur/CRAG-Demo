package ai.cerbur.crag.api.dto.query;

import java.util.Collections;
import java.util.List;

/**
 * POST /api/v1/query 成功响应 DTO.
 *
 * <p>由 API 层从 UserQueryResult 映射，保持相同业务字段，避免直接序列化内部结果类型。 sources 始终为非 null 列表（可能为空）.
 *
 * @param answer LLM 生成的回答文本
 * @param sources 引用来源列表
 * @since 2026-06-20
 */
public record UserQueryResponse(String answer, List<QuerySourceResponse> sources) {

  /** 紧凑构造器 —— 确保 sources 永远不为 null. */
  public UserQueryResponse {
    if (sources == null) {
      sources = List.of();
    }
  }

  /**
   * 返回不可修改的 sources.
   *
   * @return 不可修改的 sources 列表
   */
  @Override
  public List<QuerySourceResponse> sources() {
    return Collections.unmodifiableList(sources);
  }
}
