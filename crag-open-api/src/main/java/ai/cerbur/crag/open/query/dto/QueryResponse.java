package ai.cerbur.crag.open.query.dto;

import java.util.List;

/**
 * Open Query 响应体（plan_21/21.10）。
 *
 * @param answer 答案
 * @param sources 引用列表（reference + documentId + excerpt）
 */
public record QueryResponse(String answer, List<CitationResponse> sources) {
  public QueryResponse {
    sources = sources == null ? List.of() : List.copyOf(sources);
  }
}
