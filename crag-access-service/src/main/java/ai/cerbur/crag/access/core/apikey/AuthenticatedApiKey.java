package ai.cerbur.crag.access.core.apikey;

import java.time.Instant;

/** API Key 鉴权成功结果；不含完整 Key。 */
public record AuthenticatedApiKey(
    long apiKeyId, long tenantId, long knowledgeBaseId, Instant expiresAt) {}
