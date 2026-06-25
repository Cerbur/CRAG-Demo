package ai.cerbur.crag.knowledge.core.knowledgebase;

import ai.cerbur.crag.knowledge.dao.entity.KnowledgeBaseEntity;
import java.time.ZoneOffset;

/** KnowledgeBase 视图结果，由 core 返回给入口层，不含任何内部持久化细节。 */
public record KnowledgeBaseResult(
    long knowledgeBaseId,
    long tenantId,
    String name,
    long createdByUserId,
    String status,
    long createdAtEpochMillis,
    long updatedAtEpochMillis,
    long version) {

  public static KnowledgeBaseResult from(KnowledgeBaseEntity entity) {
    return new KnowledgeBaseResult(
        entity.getKnowledgeBaseId(),
        entity.getTenantId(),
        entity.getName(),
        entity.getCreatedByUserId(),
        entity.getStatus(),
        entity.getCreatedAt().toInstant(ZoneOffset.UTC).toEpochMilli(),
        entity.getUpdatedAt().toInstant(ZoneOffset.UTC).toEpochMilli(),
        entity.getVersion());
  }
}
