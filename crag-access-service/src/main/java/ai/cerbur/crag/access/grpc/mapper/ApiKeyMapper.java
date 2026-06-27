package ai.cerbur.crag.access.grpc.mapper;

import ai.cerbur.crag.access.core.apikey.ApiKeyResult;
import ai.cerbur.crag.access.core.apikey.ApiKeyScopeResult;
import ai.cerbur.crag.access.core.apikey.AuthenticatedApiKey;
import ai.cerbur.crag.access.core.apikey.CreatedApiKey;
import ai.cerbur.crag.access.dao.entity.ApiKeyEntity;
import ai.cerbur.crag.access.dao.entity.ApiKeyScopeEntity;
import ai.cerbur.crag.contracts.access.v1.ApiKeyScope;
import ai.cerbur.crag.contracts.access.v1.ApiKeyScopeStatus;
import ai.cerbur.crag.contracts.access.v1.ApiKeyStatus;
import ai.cerbur.crag.contracts.access.v1.ApiKeyView;

/** API Key 核心 result 与 proto 互转。core/proto 同名类型用全限定名区分。 */
public final class ApiKeyMapper {

  private ApiKeyMapper() {}

  public static ApiKeyScope toProto(ApiKeyScopeResult result) {
    return ApiKeyScope.newBuilder()
        .setKnowledgeBaseId(Long.toString(result.knowledgeBaseId()))
        .setTenantId(Long.toString(result.tenantId()))
        .setStatus(toProtoScopeStatus(result.status()))
        .setVersion(result.version())
        .build();
  }

  public static ai.cerbur.crag.contracts.access.v1.CreatedApiKey toProto(CreatedApiKey result) {
    return ai.cerbur.crag.contracts.access.v1.CreatedApiKey.newBuilder()
        .setApiKeyId(Long.toString(result.apiKeyId()))
        .setTenantId(Long.toString(result.tenantId()))
        .setKnowledgeBaseId(Long.toString(result.knowledgeBaseId()))
        .setName(result.name())
        .setCompleteKey(result.completeKey())
        .setExpiresAtEpochMillis(Long.toString(result.expiresAt().toEpochMilli()))
        .build();
  }

  public static ApiKeyView toProto(ApiKeyResult result) {
    return ApiKeyView.newBuilder()
        .setApiKeyId(Long.toString(result.apiKeyId()))
        .setTenantId(Long.toString(result.tenantId()))
        .setKnowledgeBaseId(Long.toString(result.knowledgeBaseId()))
        .setName(result.name())
        .setStatus(toProtoKeyStatus(result.status()))
        .setKeyPrefix(result.keyPrefix())
        .setCreatedAtEpochMillis(Long.toString(result.createdAt().toEpochMilli()))
        .setExpiresAtEpochMillis(Long.toString(result.expiresAt().toEpochMilli()))
        .setVersion(result.version())
        .build();
  }

  public static ai.cerbur.crag.contracts.access.v1.AuthenticatedApiKey toProto(
      AuthenticatedApiKey result) {
    return ai.cerbur.crag.contracts.access.v1.AuthenticatedApiKey.newBuilder()
        .setApiKeyId(Long.toString(result.apiKeyId()))
        .setTenantId(Long.toString(result.tenantId()))
        .setKnowledgeBaseId(Long.toString(result.knowledgeBaseId()))
        .setExpiresAtEpochMillis(Long.toString(result.expiresAt().toEpochMilli()))
        .build();
  }

  private static ApiKeyScopeStatus toProtoScopeStatus(String status) {
    return ApiKeyScopeEntity.STATUS_BLOCKED.equals(status)
        ? ApiKeyScopeStatus.SCOPE_STATUS_BLOCKED
        : ApiKeyScopeStatus.SCOPE_STATUS_ACTIVE;
  }

  private static ApiKeyStatus toProtoKeyStatus(String status) {
    return switch (status) {
      case ApiKeyEntity.STATUS_ACTIVE -> ApiKeyStatus.KEY_ACTIVE;
      case ApiKeyEntity.STATUS_DISABLED -> ApiKeyStatus.KEY_DISABLED;
      case ApiKeyEntity.STATUS_REVOKED -> ApiKeyStatus.KEY_REVOKED;
      case ApiKeyEntity.STATUS_EXPIRED -> ApiKeyStatus.KEY_EXPIRED;
      default -> ApiKeyStatus.KEY_ACTIVE;
    };
  }
}
