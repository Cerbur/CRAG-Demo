package ai.cerbur.crag.rag.grpc.mapper;

import ai.cerbur.crag.contracts.rag.v1.IngestionStatusView;
import ai.cerbur.crag.ingestion.head.IngestionStatusResult;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 将 {@link IngestionStatusResult} 映射为 gRPC {@link IngestionStatusView}（Plan 21.4）.
 *
 * <p>所有 ID 使用十进制字符串；时间转为 epoch millis（UTC）；failure 字段直接透传（已由生产端安全限长，DAO 层最大 512 字符）.
 */
public final class IngestionStatusMapper {

  private IngestionStatusMapper() {}

  public static IngestionStatusView toProto(IngestionStatusResult result) {
    return IngestionStatusView.newBuilder()
        .setTenantId(Long.toString(result.tenantId()))
        .setKnowledgeBaseId(Long.toString(result.knowledgeBaseId()))
        .setDocId(Long.toString(result.docId()))
        .setOperationVersion(Long.toString(result.operationVersion()))
        .setStatus(result.status().name())
        .setAttempt(result.attempt())
        .setJobId(Long.toString(result.jobId()))
        .setFailureCategory(result.failureCategory() == null ? "" : result.failureCategory())
        .setFailureMessage(result.failureMessage() == null ? "" : result.failureMessage())
        .setStartedAtEpochMillis(toEpochMillis(result.startedAt()))
        .setCompletedAtEpochMillis(toEpochMillis(result.completedAt()))
        .build();
  }

  private static long toEpochMillis(LocalDateTime time) {
    if (time == null) {
      return 0L;
    }
    return time.toInstant(ZoneOffset.UTC).toEpochMilli();
  }
}
