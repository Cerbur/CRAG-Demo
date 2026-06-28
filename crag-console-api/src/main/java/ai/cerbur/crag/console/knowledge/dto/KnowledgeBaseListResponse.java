package ai.cerbur.crag.console.knowledge.dto;

import java.util.List;

/**
 * KnowledgeBase 列表分页响应（plan_21/21.8）。items + nextPageToken 统一分页结构。
 *
 * <p>列表项的 {@code apiKeyReady} 由 Access Scope 查询补齐（GetScope ACTIVE 视为就绪）；查询失败时为 {@code false}。
 */
public record KnowledgeBaseListResponse(List<KnowledgeBaseResponse> items, String nextPageToken) {
  public KnowledgeBaseListResponse {
    items = items == null ? List.of() : List.copyOf(items);
  }
}
