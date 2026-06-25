package ai.cerbur.crag.knowledge.controller.smoke.dto;

/** smoke 知识库视图，不含内部持久化细节。 */
public record KnowledgeBaseSmokeResponse(
    String knowledgeBaseId, String tenantId, String name, String status) {}
