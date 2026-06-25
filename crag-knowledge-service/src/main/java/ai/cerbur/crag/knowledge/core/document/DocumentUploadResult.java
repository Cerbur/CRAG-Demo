package ai.cerbur.crag.knowledge.core.document;

/** 上传成功结果，仅携带文档元数据，不含 storage key、文件路径或文件内容。 */
public record DocumentUploadResult(
    long docId,
    long tenantId,
    long knowledgeBaseId,
    long uploadedByUserId,
    String originalFilename,
    FileType fileType,
    long sizeBytes,
    String sha256,
    String ingestionStatus,
    long operationVersion) {

  public static DocumentUploadResult of(
      long docId,
      long tenantId,
      long knowledgeBaseId,
      long uploadedByUserId,
      String originalFilename,
      FileType fileType,
      long sizeBytes,
      String sha256,
      String ingestionStatus,
      long operationVersion) {
    return new DocumentUploadResult(
        docId,
        tenantId,
        knowledgeBaseId,
        uploadedByUserId,
        originalFilename,
        fileType,
        sizeBytes,
        sha256,
        ingestionStatus,
        operationVersion);
  }
}
