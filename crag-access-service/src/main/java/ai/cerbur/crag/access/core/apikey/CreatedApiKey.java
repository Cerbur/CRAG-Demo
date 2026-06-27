package ai.cerbur.crag.access.core.apikey;

import java.time.Instant;

/** 新建/轮换后的 API Key，携带完整 Key；此后不可再读。 */
public record CreatedApiKey(
    long apiKeyId,
    long tenantId,
    long knowledgeBaseId,
    String name,
    String completeKey,
    Instant expiresAt) {}
