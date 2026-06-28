package ai.cerbur.crag.console.membership.dto;

import java.util.List;

/** 成员列表分页响应（plan_21/21.7）。items + nextPageToken 统一分页结构。 */
public record MembersListResponse(List<MemberResponse> items, String nextPageToken) {
  public MembersListResponse {
    items = items == null ? List.of() : List.copyOf(items);
  }
}
