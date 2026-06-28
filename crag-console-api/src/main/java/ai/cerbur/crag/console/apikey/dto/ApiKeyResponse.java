package ai.cerbur.crag.console.apikey.dto;

import java.time.Instant;

/**
 * API Key 安全投影（plan_21/21.9）。
 *
 * <p>用于 list/get/disable/enable/revoke 响应。只暴露可检索前缀 {@code keyPrefix}，绝不包含完整 Key 或秘密。 时间使用 RFC 3339
 * UTC；ID 使用十进制字符串。
 *
 * @param apiKeyId API Key ID，十进制字符串。
 * @param knowledgeBaseId 所属 KnowledgeBase ID，十进制字符串。
 * @param name Key 名称。
 * @param status 状态展示值：{@code ACTIVE}/{@code DISABLED}/{@code REVOKED}/{@code EXPIRED}。
 * @param keyPrefix 可检索前缀（不含完整秘密）。
 * @param createdAt 创建时间。
 * @param expiresAt 过期时间。
 */
public record ApiKeyResponse(
    String apiKeyId,
    String knowledgeBaseId,
    String name,
    String status,
    String keyPrefix,
    Instant createdAt,
    Instant expiresAt) {
  /**
   * {@inheritDoc}
   *
   * <p>本投影只含前缀，已天然不泄漏完整秘密；保持默认 record 行为，便于序列化测试断言前缀可见、完整 Key 字段不存在。
   */
}
