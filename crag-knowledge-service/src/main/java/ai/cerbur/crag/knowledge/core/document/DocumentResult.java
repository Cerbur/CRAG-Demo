package ai.cerbur.crag.knowledge.core.document;

import ai.cerbur.crag.knowledge.dao.entity.DocumentEntity;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Document 完整视图，由 core 返回给入口层；不含 storage key、文件路径或文件内容。
 *
 * <p>plan_21/21.3 起增加完整摄取投影字段：{@code ingestionAttempt}、{@code ingestionJobId}、{@code
 * failureCategory}、 {@code failureMessage}、{@code startedAtEpochMillis}、{@code
 * completedAtEpochMillis}、{@code nextRetryAtEpochMillis}。 PENDING 文档的失败字段与 jobId 为 null；时间字段 epoch
 * millis 在 null 时返回 0（与 proto int64 默认值一致）。
 */
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
    long updatedAtEpochMillis,
    // --- router4 摄取投影与失败字段（plan_21/21.3）---
    int ingestionAttempt,
    Long ingestionJobId,
    String failureCategory,
    String failureMessage,
    Long startedAtEpochMillis,
    Long completedAtEpochMillis,
    Long nextRetryAtEpochMillis) {

  /** 从持久化实体构建视图。 */
  public static DocumentResult from(DocumentEntity entity) {
    Integer attempt = entity.getIngestionAttempt();
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
        epochMillis(entity.getUpdatedAt()),
        attempt == null ? 0 : attempt,
        entity.getIngestionJobId(),
        entity.getFailureCategory(),
        entity.getFailureMessage(),
        epochMillisNullable(entity.getStartedAt()),
        epochMillisNullable(entity.getCompletedAt()),
        epochMillisNullable(entity.getNextRetryAt()));
  }

  /** LocalDateTime（UTC）转 epoch 毫秒。 */
  static long epochMillis(LocalDateTime value) {
    return value.toInstant(ZoneOffset.UTC).toEpochMilli();
  }

  /** nullable LocalDateTime（UTC）转 epoch 毫秒；null 返回 null（调用方按 proto 默认 0 处理）。 */
  static Long epochMillisNullable(LocalDateTime value) {
    return value == null ? null : value.toInstant(ZoneOffset.UTC).toEpochMilli();
  }
}
