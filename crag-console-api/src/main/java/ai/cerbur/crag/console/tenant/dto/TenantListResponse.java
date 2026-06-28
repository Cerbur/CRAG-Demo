package ai.cerbur.crag.console.tenant.dto;

import ai.cerbur.crag.console.auth.dto.TenantSummaryResponse;
import java.util.List;

/** Tenant 列表分页响应（plan_21/21.7）。复用 21.6 的 TenantSummaryResponse；items + nextPageToken。 */
public record TenantListResponse(List<TenantSummaryResponse> items, String nextPageToken) {
  public TenantListResponse {
    items = items == null ? List.of() : List.copyOf(items);
  }
}
