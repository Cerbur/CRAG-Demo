package ai.cerbur.crag.knowledge.grpc.mapper;

import ai.cerbur.crag.contracts.knowledge.v1.Document;
import ai.cerbur.crag.knowledge.core.document.DocumentResult;

/** {@link DocumentResult} 与 proto {@link Document} 互转；不含 storage key、路径或文件内容。 */
public final class DocumentMapper {

  private DocumentMapper() {}

  public static Document toProto(DocumentResult result) {
    Document.Builder builder =
        Document.newBuilder()
            .setDocId(Long.toString(result.docId()))
            .setKnowledgeBaseId(Long.toString(result.knowledgeBaseId()))
            .setTenantId(Long.toString(result.tenantId()))
            .setUploadedByUserId(Long.toString(result.uploadedByUserId()))
            .setOriginalFilename(result.originalFilename())
            .setFileType(result.fileType().name())
            .setSizeBytes(result.sizeBytes())
            .setSha256(result.sha256())
            .setIngestionStatus(result.ingestionStatus())
            .setOperationVersion(result.operationVersion())
            .setCreatedAtEpochMillis(result.createdAtEpochMillis())
            .setUpdatedAtEpochMillis(result.updatedAtEpochMillis())
            // router4 摄取投影字段（13–19）；nullable 字段 null 时取 proto 默认 0/空串。
            .setIngestionAttempt(result.ingestionAttempt());
    if (result.ingestionJobId() != null) {
      builder.setIngestionJobId(Long.toString(result.ingestionJobId()));
    }
    if (result.failureCategory() != null) {
      builder.setFailureCategory(result.failureCategory());
    }
    if (result.failureMessage() != null) {
      builder.setFailureMessage(result.failureMessage());
    }
    if (result.startedAtEpochMillis() != null) {
      builder.setStartedAtEpochMillis(result.startedAtEpochMillis());
    }
    if (result.completedAtEpochMillis() != null) {
      builder.setCompletedAtEpochMillis(result.completedAtEpochMillis());
    }
    if (result.nextRetryAtEpochMillis() != null) {
      builder.setNextRetryAtEpochMillis(result.nextRetryAtEpochMillis());
    }
    return builder.build();
  }
}
