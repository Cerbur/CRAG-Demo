package ai.cerbur.crag.ingestion.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.cerbur.crag.ingestion.producer.RagIngestionStatusEventTypes;
import ai.cerbur.crag.ingestion.producer.RagIngestionStatusEventWriter;
import ai.cerbur.crag.storage.ChunkDao;
import ai.cerbur.crag.storage.IngestionJobConflictException;
import ai.cerbur.crag.storage.IngestionJobDao;
import ai.cerbur.crag.storage.entity.IngestionJob;
import ai.cerbur.crag.storage.entity.IngestionJobStatus;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * IngestionJobService 单元测试 —— 验证幂等解析决策与状态机推进（Plan 19）.
 *
 * <p>DAO 通过 Mockito 隔离，聚焦服务自身的幂等决策与 CAS 冲突传播.
 */
@DisplayName("IngestionJobService 幂等解析与状态机")
@ExtendWith(MockitoExtension.class)
class IngestionJobServiceTest {

  @Mock private IngestionJobDao ingestionJobDao;

  @Mock private ChunkDao chunkDao;

  @Mock private RagIngestionStatusEventWriter statusEventWriter;

  @InjectMocks private IngestionJobService service;

  @Nested
  @DisplayName("resolve 幂等解析")
  class Resolve {

    @Test
    @DisplayName("首次见到业务键 → 新建 PENDING，fresh=true 且 needsProcessing=true")
    void freshKeyCreatesPendingJob() {
      when(ingestionJobDao.findByDocIdAndOperationVersion(100L, 1L)).thenReturn(Optional.empty());
      IngestionJob created = pendingJob(100L, 1L);
      when(ingestionJobDao.findOrCreate(
              anyLong(), anyLong(), eq(100L), eq(1L), anyString(), anyLong(), anyString()))
          .thenReturn(created);

      IngestionJobResolution resolution = service.resolve(7L, 9L, 100L, 1L, "TXT", 42L, "abc123");

      assertThat(resolution.fresh()).isTrue();
      assertThat(resolution.needsProcessing()).isTrue();
      assertThat(resolution.job().getStatus()).isEqualTo(IngestionJobStatus.PENDING);
      verify(ingestionJobDao).findOrCreate(7L, 9L, 100L, 1L, "TXT", 42L, "abc123");
    }

    @Test
    @DisplayName("重复事件命中 PENDING → fresh=false，needsProcessing=true")
    void duplicatePendingKeepsNeedsProcessing() {
      IngestionJob existing = pendingJob(100L, 1L);
      when(ingestionJobDao.findByDocIdAndOperationVersion(100L, 1L))
          .thenReturn(Optional.of(existing));

      IngestionJobResolution resolution = service.resolve(7L, 9L, 100L, 1L, "TXT", 42L, "abc123");

      assertThat(resolution.fresh()).isFalse();
      assertThat(resolution.needsProcessing()).isTrue();
    }

    @Test
    @DisplayName("重复事件命中 READY → 不再处理")
    void duplicateReadySkipsProcessing() {
      IngestionJob ready = jobWithStatus(100L, 1L, IngestionJobStatus.READY);
      when(ingestionJobDao.findByDocIdAndOperationVersion(100L, 1L)).thenReturn(Optional.of(ready));

      IngestionJobResolution resolution = service.resolve(7L, 9L, 100L, 1L, "TXT", 42L, "abc123");

      assertThat(resolution.fresh()).isFalse();
      assertThat(resolution.needsProcessing()).isFalse();
    }

    @Test
    @DisplayName("重复事件命中 FAILED → 不再处理，不自动重跑")
    void duplicateFailedSkipsProcessing() {
      IngestionJob failed = jobWithStatus(100L, 1L, IngestionJobStatus.FAILED);
      when(ingestionJobDao.findByDocIdAndOperationVersion(100L, 1L))
          .thenReturn(Optional.of(failed));

      IngestionJobResolution resolution = service.resolve(7L, 9L, 100L, 1L, "TXT", 42L, "abc123");

      assertThat(resolution.needsProcessing()).isFalse();
    }
  }

  @Nested
  @DisplayName("状态机推进")
  class StateTransition {

    @Test
    @DisplayName("markProcessing → 委托 DAO 并写 INGESTION_PROCESSING 事件")
    void markProcessingDelegates() {
      IngestionJob job = pendingJob(100L, 1L);

      service.markProcessing(job);

      verify(ingestionJobDao).markProcessing(eq(job), any(LocalDateTime.class));
      verify(statusEventWriter)
          .write(
              eq(job), eq(RagIngestionStatusEventTypes.INGESTION_PROCESSING), isNull(), isNull());
    }

    @Test
    @DisplayName("markProcessing CAS 冲突 → 传播 IngestionJobConflictException")
    void markProcessingPropagatesConflict() {
      IngestionJob job = pendingJob(100L, 1L);
      doThrow(new IngestionJobConflictException(100L, 1L, "conflict"))
          .when(ingestionJobDao)
          .markProcessing(eq(job), any());

      assertThatThrownBy(() -> service.markProcessing(job))
          .isInstanceOf(IngestionJobConflictException.class);
    }

    @Test
    @DisplayName("markReady → 委托 DAO 并写 INGESTION_READY 事件")
    void markReadyDelegates() {
      IngestionJob job = jobWithStatus(100L, 1L, IngestionJobStatus.PROCESSING);

      service.markReady(job);

      verify(ingestionJobDao).markReady(eq(job), any(LocalDateTime.class));
      verify(statusEventWriter)
          .write(eq(job), eq(RagIngestionStatusEventTypes.INGESTION_READY), isNull(), isNull());
    }

    @Test
    @DisplayName("markFailed → 委托 DAO 携带分类与安全摘要，并写 INGESTION_FAILED 事件")
    void markFailedDelegatesWithCategoryAndSafeMessage() {
      IngestionJob job = jobWithStatus(100L, 1L, IngestionJobStatus.PROCESSING);

      service.markFailed(job, IngestionJobFailureCategory.FILE_DECODE_FAILED, "decode error");

      verify(ingestionJobDao)
          .markFailed(
              eq(job), any(LocalDateTime.class), eq("FILE_DECODE_FAILED"), eq("decode error"));
      verify(statusEventWriter)
          .write(
              eq(job),
              eq(RagIngestionStatusEventTypes.INGESTION_FAILED),
              eq("FILE_DECODE_FAILED"),
              eq("decode error"));
    }
  }

  @Nested
  @DisplayName("失败摘要安全收敛")
  class FailureMessageSanitize {

    @Test
    @DisplayName("null → 回退到 UNKNOWN 分类名")
    void nullFallsBackToUnknown() {
      assertThat(IngestionJobService.sanitizeFailureMessage(null)).isEqualTo("UNKNOWN");
    }

    @Test
    @DisplayName("空白 → 回退到 UNKNOWN 分类名")
    void blankFallsBackToUnknown() {
      assertThat(IngestionJobService.sanitizeFailureMessage("   \n ")).isEqualTo("UNKNOWN");
    }

    @Test
    @DisplayName("换行 → 折叠为单行空格")
    void multilineCollapsedToOneLine() {
      assertThat(IngestionJobService.sanitizeFailureMessage("line1\nline2\r\nline3"))
          .isEqualTo("line1 line2  line3");
    }

    @Test
    @DisplayName("超长 → 截断到 200 字符")
    void tooLongTruncated() {
      String longMsg = "x".repeat(500);

      String sanitized = IngestionJobService.sanitizeFailureMessage(longMsg);

      assertThat(sanitized).hasSize(200);
    }
  }

  private static IngestionJob pendingJob(long docId, long operationVersion) {
    return jobWithStatus(docId, operationVersion, IngestionJobStatus.PENDING);
  }

  private static IngestionJob jobWithStatus(
      long docId, long operationVersion, IngestionJobStatus status) {
    IngestionJob job =
        IngestionJob.createPending(7L, 9L, docId, operationVersion, "TXT", 42L, "abc");
    job.setStatus(status);
    job.setVersion(0);
    return job;
  }
}
