package ai.cerbur.crag.console.apikey.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/**
 * 一次性完整秘密 Key 响应（plan_21/21.9）。
 *
 * <p>仅在 create 与 rotate 成功时返回。{@code completeKey} 是完整 Key（{@code crag_<前缀>_<秘密>}），此后不可再读， 因此必须保证：
 *
 * <ul>
 *   <li>{@link #toString()} 不输出 {@code completeKey}，避免日志/异常/堆栈泄漏。
 *   <li>日志、异常 message、错误响应一律不包含 {@code completeKey}。
 *   <li>列表与详情响应使用 {@link ApiKeyResponse}（仅前缀），绝不复用本类型。
 * </ul>
 *
 * @param apiKeyId 新 Key ID，十进制字符串。
 * @param knowledgeBaseId 所属 KnowledgeBase ID，十进制字符串。
 * @param name Key 名称。
 * @param completeKey 完整 Key，仅此一次返回。
 * @param expiresAt 过期时间。
 */
public record CreatedApiKeyResponse(
    String apiKeyId,
    String knowledgeBaseId,
    String name,
    @JsonProperty("completeKey") String completeKey,
    Instant expiresAt) {

  /**
   * 屏蔽 {@code completeKey} 的字符串表示，防止日志、异常 message 或无意打印泄漏完整 Key。
   *
   * <p>序列化使用 Jackson（输出 JSON 包含 {@code completeKey}），本方法只影响 {@code toString()} 调用路径。
   */
  @Override
  public String toString() {
    return "CreatedApiKeyResponse[apiKeyId="
        + apiKeyId
        + ", knowledgeBaseId="
        + knowledgeBaseId
        + ", name="
        + name
        + ", completeKey=***REDACTED***"
        + ", expiresAt="
        + expiresAt
        + "]";
  }
}
