package ai.cerbur.crag.access.core.apikey;

import java.util.List;

/**
 * API Key 分页结果（plan_21/21.2）。
 *
 * @param items 当前页 Key 安全投影
 * @param nextPageToken 下一页游标（上一页最后一条 apiKeyId 的十进制字符串）；无更多数据时为 null
 */
public record ApiKeyListPage(List<ApiKeyResult> items, String nextPageToken) {
  public ApiKeyListPage {
    items = items == null ? List.of() : List.copyOf(items);
  }
}
