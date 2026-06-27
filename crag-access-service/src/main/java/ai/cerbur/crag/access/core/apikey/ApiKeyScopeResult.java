package ai.cerbur.crag.access.core.apikey;

import ai.cerbur.crag.access.dao.entity.ApiKeyScopeEntity;

/** KnowledgeBase 授权投影（Scope）视图。 */
public record ApiKeyScopeResult(long knowledgeBaseId, long tenantId, String status, long version) {
  public static ApiKeyScopeResult from(ApiKeyScopeEntity entity) {
    return new ApiKeyScopeResult(
        entity.getKnowledgeBaseId(), entity.getTenantId(), entity.getStatus(), entity.getVersion());
  }
}
