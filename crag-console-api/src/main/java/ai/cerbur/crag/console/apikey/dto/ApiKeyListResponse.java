package ai.cerbur.crag.console.apikey.dto;

import java.util.List;

/**
 * API Key 列表响应（plan_21/21.9）。
 *
 * <p>列表项只包含 {@link ApiKeyResponse}（前缀投影），不含完整 Key。{@code nextPageToken} 为空表示无下一页。
 *
 * @param items Key 投影列表。
 * @param nextPageToken 下一页游标；无更多数据时为 {@code null}。
 */
public record ApiKeyListResponse(List<ApiKeyResponse> items, String nextPageToken) {}
