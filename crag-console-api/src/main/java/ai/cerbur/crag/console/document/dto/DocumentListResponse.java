package ai.cerbur.crag.console.document.dto;

import java.util.List;

/** Document 列表分页响应（plan_21/21.8）。items + nextPageToken 统一分页结构。 */
public record DocumentListResponse(List<DocumentResponse> items, String nextPageToken) {
  public DocumentListResponse {
    items = items == null ? List.of() : List.copyOf(items);
  }
}
