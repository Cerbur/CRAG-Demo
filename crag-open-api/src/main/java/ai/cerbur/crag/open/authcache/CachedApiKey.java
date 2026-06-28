package ai.cerbur.crag.open.authcache;

import java.time.Instant;

/**
 * Open 本地缓存值（plan_21/21.10）。
 *
 * <p>只含定位与版本水位，<strong>不</strong>包含完整 Key 或指纹。完整 Key 只用于计算 SHA-256 指纹作为缓存键， 不写入缓存值、日志、指标或异常。
 *
 * @param apiKeyId API Key ID
 * @param tenantId 租户 ID
 * @param knowledgeBaseId 知识库 ID（由 Key 决定，不接受客户端传入）
 * @param keyVersion Key 失效版本水位
 * @param scopeVersion Scope 失效版本水位
 * @param expiresAt Key 自然过期时间
 */
public record CachedApiKey(
    long apiKeyId,
    long tenantId,
    long knowledgeBaseId,
    long keyVersion,
    long scopeVersion,
    Instant expiresAt) {}
