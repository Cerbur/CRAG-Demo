package ai.cerbur.crag.access.core.apikey;

import ai.cerbur.crag.access.dao.entity.ApiKeyEntity;
import java.time.Instant;
import java.time.ZoneOffset;

/** API Key 视图，不含完整 Key 或秘密，只暴露可检索前缀。 */
public record ApiKeyResult(
    long apiKeyId,
    long tenantId,
    long knowledgeBaseId,
    String name,
    String status,
    String keyPrefix,
    Instant createdAt,
    Instant expiresAt,
    long version) {
  public static ApiKeyResult from(ApiKeyEntity entity) {
    return new ApiKeyResult(
        entity.getApiKeyId(),
        entity.getTenantId(),
        entity.getKnowledgeBaseId(),
        entity.getName(),
        entity.getStatus(),
        entity.getKeyPrefix(),
        entity.getCreatedAt().toInstant(ZoneOffset.UTC),
        entity.getExpiresAt().toInstant(ZoneOffset.UTC),
        entity.getVersion());
  }
}
