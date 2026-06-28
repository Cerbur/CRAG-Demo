package ai.cerbur.crag.console.apikey.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * CreatedApiKeyResponse 秘密卫生单测（plan_21/21.9）。
 *
 * <p>核心约束：完整 Key 只能通过一次性 JSON 序列化路径返回给前端，绝不出现在 {@code toString()}、日志、异常 message 或错误响应。本测试锁定：
 *
 * <ul>
 *   <li>{@link CreatedApiKeyResponse#toString()} 不含完整 Key（防止 log.error(resp) 或异常拼接泄漏）。
 *   <li>序列化字段 {@code ApiKeyResponse}（list/get/disable/enable/revoke）无 {@code completeKey} 字段。
 *   <li>{@link CreatedApiKeyResponse} 的 JSON 包含 {@code completeKey}（一次性返回路径正常工作）。
 * </ul>
 */
class CreatedApiKeySecrecyTest {

  private static final String SECRET = "crag_abc_supersecret_value";
  private final ObjectMapper om = new ObjectMapper();

  @Test
  @DisplayName("CreatedApiKeyResponse.toString() 不泄漏 completeKey")
  void toStringRedactsCompleteKey() {
    CreatedApiKeyResponse resp =
        new CreatedApiKeyResponse(
            "200", "100", "prod-key", SECRET, Instant.parse("2026-09-29T00:00:00Z"));

    String s = resp.toString();
    assertThat(s).doesNotContain(SECRET);
    assertThat(s).contains("***REDACTED***");
  }

  @Test
  @DisplayName("CreatedApiKeyResponse JSON 序列化包含 completeKey（一次性返回路径）")
  void jsonSerializesCompleteKey() throws Exception {
    CreatedApiKeyResponse resp =
        new CreatedApiKeyResponse(
            "200", "100", "prod-key", SECRET, Instant.parse("2026-09-29T00:00:00Z"));

    String json = om.writeValueAsString(resp);
    assertThat(json).contains("\"completeKey\":\"" + SECRET + "\"");
  }

  @Test
  @DisplayName("ApiKeyResponse（list/get 投影）JSON 不含 completeKey 字段")
  void apiKeyResponseHasNoCompleteKey() throws Exception {
    ApiKeyResponse resp =
        new ApiKeyResponse(
            "200",
            "100",
            "prod-key",
            "ACTIVE",
            "crag_abc",
            Instant.parse("2026-06-29T00:00:00Z"),
            Instant.parse("2026-09-29T00:00:00Z"));

    String json = om.writeValueAsString(resp);
    assertThat(json).doesNotContain("completeKey");
    assertThat(json).contains("\"keyPrefix\":\"crag_abc\"");
  }

  @Test
  @DisplayName("ApiKeyListResponse JSON 项不含 completeKey")
  void listResponseItemsHaveNoCompleteKey() throws Exception {
    ApiKeyListResponse list =
        new ApiKeyListResponse(
            java.util.List.of(
                new ApiKeyResponse(
                    "200",
                    "100",
                    "k",
                    "ACTIVE",
                    "crag_abc",
                    Instant.parse("2026-06-29T00:00:00Z"),
                    Instant.parse("2026-09-29T00:00:00Z"))),
            null);

    String json = om.writeValueAsString(list);
    assertThat(json).doesNotContain("completeKey");
    assertThat(json).contains("\"keyPrefix\":\"crag_abc\"");
  }
}
