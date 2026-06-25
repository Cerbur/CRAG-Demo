package ai.cerbur.crag.knowledge.core.document;

import ai.cerbur.crag.knowledge.dao.entity.DocumentEntity;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** Document 完整视图，由 core 返回给入口层；不含 storage key、文件路径或文件内容。 */
public record DocumentResult(
    long docId,
    long tenantId,
    long knowledgeBaseId,
    long uploadedByUserId,
    String originalFilename,
    FileType fileType,
    long sizeBytes,
    String sha256,
    String ingestionStatus,
    long operationVersion,
    long createdAtEpochMillis,
    long updatedAtEpochMillis) {

  /** 从持久化实体构建视图。 */
  public static DocumentResult from(DocumentEntity entity) {
    return new DocumentResult(
        entity.getDocId(),
        entity.getTenantId(),
        entity.getKnowledgeBaseId(),
        entity.getUploadedByUserId(),
        entity.getOriginalFilename(),
        FileType.fromDeclared(entity.getFileType()),
        entity.getSizeBytes(),
        entity.getSha256(),
        entity.getIngestionStatus(),
        entity.getOperationVersion(),
        epochMillis(entity.getCreatedAt()),
        epochMillis(entity.getUpdatedAt()));
  }

  /** LocalDateTime（UTC）转 epoch 毫秒。 */
  static long epochMillis(LocalDateTime value) {
    return value.toInstant(ZoneOffset.UTC).toEpochMilli();
  }
}
