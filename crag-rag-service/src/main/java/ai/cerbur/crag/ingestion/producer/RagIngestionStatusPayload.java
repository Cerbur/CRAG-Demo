package ai.cerbur.crag.ingestion.producer;

/**
 * RAG ingestion 状态事件 payload（Plan 19）.
 *
 * <p>只携带下游（未来 Knowledge 状态消费）所需的安全字段。<strong>禁止</strong>包含文件内容、storage key、文件路径、Prompt、 Context
 * 或向量。{@code failureMessage} 必须是安全短摘要，不透传 SQL、堆栈或文件内容.
 *
 * @param tenantId 租户 ID
 * @param knowledgeBaseId 知识库 ID
 * @param docId 文档 ID
 * @param operationVersion 文档逻辑操作版本
 * @param jobId Ingestion Job ID
 * @param status Job 状态展示值（PROCESSING / READY / FAILED）
 * @param failureCategory 失败分类（仅 FAILED 时填充，否则 null）
 * @param failureMessage 失败安全短摘要（仅 FAILED 时填充，否则 null）
 * @param occurredAt 事件发生时间（epoch millis）
 */
public record RagIngestionStatusPayload(
    long tenantId,
    long knowledgeBaseId,
    long docId,
    long operationVersion,
    long jobId,
    String status,
    String failureCategory,
    String failureMessage,
    long occurredAt) {}
