package ai.cerbur.crag.knowledge.controller.smoke.dto;

/** smoke 文档视图；不含 storage key、文件路径或文件内容。 */
public record DocumentSmokeResponse(
    String docId,
    String knowledgeBaseId,
    String tenantId,
    String fileType,
    long sizeBytes,
    String sha256,
    String ingestionStatus,
    long operationVersion) {}
