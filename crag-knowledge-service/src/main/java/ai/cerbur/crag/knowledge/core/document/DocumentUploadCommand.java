package ai.cerbur.crag.knowledge.core.document;

/** 单次客户端流式上传的 metadata 命令，内容字节通过流式追加提供。 */
public record DocumentUploadCommand(
    long tenantId,
    long knowledgeBaseId,
    long uploadedByUserId,
    String originalFilename,
    FileType fileType,
    long declaredSizeBytes,
    String declaredSha256) {

  public DocumentUploadCommand {
    if (tenantId <= 0) {
      throw new IllegalArgumentException("tenantId must be positive");
    }
    if (knowledgeBaseId <= 0) {
      throw new IllegalArgumentException("knowledgeBaseId must be positive");
    }
    if (uploadedByUserId <= 0) {
      throw new IllegalArgumentException("uploadedByUserId must be positive");
    }
    if (originalFilename == null || originalFilename.isBlank()) {
      throw new IllegalArgumentException("originalFilename must not be blank");
    }
    if (fileType == null) {
      throw new IllegalArgumentException("fileType must not be null");
    }
    if (declaredSizeBytes <= 0) {
      throw new IllegalArgumentException("declaredSizeBytes must be positive");
    }
    if (declaredSha256 == null || declaredSha256.isBlank()) {
      throw new IllegalArgumentException("declaredSha256 must not be blank");
    }
  }
}
