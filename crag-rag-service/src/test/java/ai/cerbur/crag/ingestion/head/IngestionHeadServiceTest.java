package ai.cerbur.crag.ingestion.head;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.cerbur.crag.ingestion.producer.RagIngestionStatusEventWriter;
import ai.cerbur.crag.storage.IngestionHeadDao;
import ai.cerbur.crag.storage.IngestionJobDao;
import ai.cerbur.crag.storage.result.IngestionHead;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** IngestionHeadService 单元测试（Plan 21.4）：验证 head 单调推进、低版本/等版本幂等 ACK 与高版本 SUPERSEDED 编排. */
@DisplayName("IngestionHeadService 单调推进与旧 Job 取代")
@ExtendWith(MockitoExtension.class)
class IngestionHeadServiceTest {

  private static final long TENANT = 101L;
  private static final long KB = 200L;
  private static final long DOC = 1001L;

  @Mock private IngestionHeadDao ingestionHeadDao;
  @Mock private IngestionJobDao ingestionJobDao;
  @Mock private RagIngestionStatusEventWriter statusEventWriter;

  @InjectMocks private IngestionHeadService service;

  @Nested
  @DisplayName("advance 单调推进")
  class Advance {

    @Test
    @DisplayName("首次事件 → 创建 head 并允许处理（EQUAL/ADVANCED）")
    void firstEventCreatesHeadAndAllowsProcessing() {
      when(ingestionHeadDao.findOrCreate(KB, DOC, 1L))
          .thenReturn(new IngestionHead(KB, DOC, 1L, 0L));

      HeadAdvanceResult result = service.advance(TENANT, KB, DOC, 1L);

      assertThat(result.shouldProcess()).isTrue();
      assertThat(result.head().operationVersion()).isEqualTo(1L);
    }

    @Test
    @DisplayName("高版本事件 → CAS 推进成功、旧 Job 标记 SUPERSEDED、允许处理")
    void higherVersionAdvancesAndSupersedes() {
      when(ingestionHeadDao.findOrCreate(KB, DOC, 2L))
          .thenReturn(new IngestionHead(KB, DOC, 1L, 0L));
      when(ingestionHeadDao.advance(any(IngestionHead.class), eq(2L))).thenReturn(1);
      when(ingestionJobDao.markSuperseded(DOC, 2L)).thenReturn(1);

      HeadAdvanceResult result = service.advance(TENANT, KB, DOC, 2L);

      assertThat(result.shouldProcess()).isTrue();
      assertThat(result.outcome()).isEqualTo(HeadAdvanceOutcome.ADVANCED);
      assertThat(result.head().operationVersion()).isEqualTo(2L);
      verify(ingestionJobDao).markSuperseded(DOC, 2L);
    }

    @Test
    @DisplayName("低版本事件 → 幂等 ACK，不继续处理、不标记 SUPERSEDED")
    void lowerVersionAcksAndDoesNotProcess() {
      when(ingestionHeadDao.findOrCreate(KB, DOC, 1L))
          .thenReturn(new IngestionHead(KB, DOC, 5L, 3L));

      HeadAdvanceResult result = service.advance(TENANT, KB, DOC, 1L);

      assertThat(result.shouldProcess()).isFalse();
      assertThat(result.outcome()).isEqualTo(HeadAdvanceOutcome.LOW_VERSION_ACK);
      verify(ingestionJobDao, never()).markSuperseded(anyLong(), anyLong());
    }

    @Test
    @DisplayName("等版本事件 → 幂等 ACK，允许处理（重复 DOC_UPLOADED 可继续 Job 编排）")
    void equalVersionAcksAndAllowsProcessing() {
      when(ingestionHeadDao.findOrCreate(KB, DOC, 3L))
          .thenReturn(new IngestionHead(KB, DOC, 3L, 2L));

      HeadAdvanceResult result = service.advance(TENANT, KB, DOC, 3L);

      assertThat(result.shouldProcess()).isTrue();
      assertThat(result.outcome()).isEqualTo(HeadAdvanceOutcome.EQUAL_VERSION_ACK);
      verify(ingestionJobDao, never()).markSuperseded(anyLong(), anyLong());
    }

    @Test
    @DisplayName("CAS 并发抢占失败且刷新后版本相等 → 幂等 ACK，允许处理")
    void concurrentAdvanceEqualAfterRefreshAcksEqual() {
      when(ingestionHeadDao.findOrCreate(KB, DOC, 3L))
          .thenReturn(new IngestionHead(KB, DOC, 2L, 1L));
      when(ingestionHeadDao.advance(any(IngestionHead.class), eq(3L))).thenReturn(0);
      when(ingestionHeadDao.findByDocId(DOC))
          .thenReturn(Optional.of(new IngestionHead(KB, DOC, 3L, 2L)));

      HeadAdvanceResult result = service.advance(TENANT, KB, DOC, 3L);

      assertThat(result.shouldProcess()).isTrue();
      assertThat(result.outcome()).isEqualTo(HeadAdvanceOutcome.EQUAL_VERSION_ACK);
    }

    @Test
    @DisplayName("CAS 并发抢占失败且刷新后版本更高 → 幂等 ACK，不继续处理")
    void concurrentAdvanceHigherAfterRefreshAcksLow() {
      when(ingestionHeadDao.findOrCreate(KB, DOC, 3L))
          .thenReturn(new IngestionHead(KB, DOC, 2L, 1L));
      when(ingestionHeadDao.advance(any(IngestionHead.class), eq(3L))).thenReturn(0);
      when(ingestionHeadDao.findByDocId(DOC))
          .thenReturn(Optional.of(new IngestionHead(KB, DOC, 4L, 2L)));

      HeadAdvanceResult result = service.advance(TENANT, KB, DOC, 3L);

      assertThat(result.shouldProcess()).isFalse();
      assertThat(result.outcome()).isEqualTo(HeadAdvanceOutcome.LOW_VERSION_ACK);
    }
  }

  private static long eq(long v) {
    return org.mockito.ArgumentMatchers.eq(v);
  }
}
