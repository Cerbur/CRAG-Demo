package ai.cerbur.crag.knowledge.producer;

/**
 * {@code DOC_UPLOADED} 事件 payload。
 *
 * <p>仅携带下游 RAG 摄取所需的安全字段；<strong>禁止</strong>包含文件路径、storage key、原始文件内容、Prompt 或 Context。
 *
 * @param tenantId 租户 ID
 * @param knowledgeBaseId 知识库 ID
 * @param docId 文档 ID（事件 resourceId）
 * @param operationVersion 文档逻辑操作版本
 * @param fileType 文件类型展示值（TXT/MARKDOWN）
 * @param sizeBytes 文件字节数
 * @param sha256 文件 sha256（十六进制小写）
 */
public record DocumentUploadedPayload(
    long tenantId,
    long knowledgeBaseId,
    long docId,
    long operationVersion,
    String fileType,
    long sizeBytes,
    String sha256) {}
