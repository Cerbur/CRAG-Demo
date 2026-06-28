package ai.cerbur.crag.access.core.apikey;

import ai.cerbur.crag.access.dao.entity.ApiKeyScopeEntity;

/**
 * KnowledgeBase 授权投影（Scope）视图。
 *
 * @param knowledgeBaseId 知识库 ID
 * @param tenantId 租户 ID
 * @param status Scope 状态（ACTIVE/BLOCKED）
 * @param version Scope CAS 版本
 * @param keyVersion Key 版本水位（plan_21/21.2），Open 缓存据此拒绝旧鉴权结果；Scope 级别与 version 一致
 * @param scopeVersion Scope 版本水位（plan_21/21.2），BlockScope 后递增使旧鉴权结果失效
 */
public record ApiKeyScopeResult(
    long knowledgeBaseId,
    long tenantId,
    String status,
    long version,
    long keyVersion,
    long scopeVersion) {
  public static ApiKeyScopeResult from(ApiKeyScopeEntity entity) {
    // Scope 级别缓存驱逐以 Scope version 为水位；keyVersion 与 scopeVersion 一致，使 Open 可在不持有
    // 单 Key 版本时也能识别 Scope 变更并失效旧缓存。
    return new ApiKeyScopeResult(
        entity.getKnowledgeBaseId(),
        entity.getTenantId(),
        entity.getStatus(),
        entity.getVersion(),
        entity.getVersion(),
        entity.getVersion());
  }
}
