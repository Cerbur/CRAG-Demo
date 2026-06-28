package ai.cerbur.crag.access.core.apikey;

import java.time.Instant;

/**
 * API Key 鉴权成功结果；不含完整 Key。
 *
 * @param apiKeyId API Key ID
 * @param tenantId 租户 ID
 * @param knowledgeBaseId 知识库 ID
 * @param expiresAt 过期时间
 * @param keyVersion Key 自身版本水位（plan_21/21.2），Open 缓存据此拒绝旧鉴权结果
 * @param scopeVersion Key 所属 Scope 的版本水位（plan_21/21.2），Scope Block 后旧鉴权结果失效
 */
public record AuthenticatedApiKey(
    long apiKeyId,
    long tenantId,
    long knowledgeBaseId,
    Instant expiresAt,
    long keyVersion,
    long scopeVersion) {}
