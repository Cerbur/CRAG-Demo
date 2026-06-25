package ai.cerbur.crag.knowledge.grpc.mapper;

import ai.cerbur.crag.contracts.knowledge.v1.Document;
import ai.cerbur.crag.knowledge.core.document.DocumentResult;

/** {@link DocumentResult} 与 proto {@link Document} 互转；不含 storage key、路径或文件内容。 */
public final class DocumentMapper {

  private DocumentMapper() {}

  public static Document toProto(DocumentResult result) {
    return Document.newBuilder()
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
        .build();
  }
}
