package ai.cerbur.crag.knowledge.grpc.mapper;

import ai.cerbur.crag.contracts.knowledge.v1.KnowledgeBase;
import ai.cerbur.crag.knowledge.core.knowledgebase.KnowledgeBaseResult;

/** {@link KnowledgeBaseResult} 与 proto {@link KnowledgeBase} 互转。 */
public final class KnowledgeBaseMapper {

  private KnowledgeBaseMapper() {}

  public static KnowledgeBase toProto(KnowledgeBaseResult result) {
    return KnowledgeBase.newBuilder()
        .setKnowledgeBaseId(Long.toString(result.knowledgeBaseId()))
        .setTenantId(Long.toString(result.tenantId()))
        .setName(result.name())
        .setCreatedByUserId(Long.toString(result.createdByUserId()))
        .setStatus(result.status())
        .setCreatedAtEpochMillis(result.createdAtEpochMillis())
        .setUpdatedAtEpochMillis(result.updatedAtEpochMillis())
        .setVersion(result.version())
        .build();
  }
}
