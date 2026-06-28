package ai.cerbur.crag.knowledge.reconcile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.cerbur.crag.knowledge.core.document.DocumentResult;
import ai.cerbur.crag.knowledge.core.ingestion.IngestionRetryService;
import ai.cerbur.crag.knowledge.core.ingestion.RetryPolicy;
import ai.cerbur.crag.knowledge.dao.DocumentDao;
import ai.cerbur.crag.knowledge.dao.VersionConflictException;
import ai.cerbur.crag.knowledge.dao.entity.DocumentEntity;
import ai.cerbur.crag.knowledge.metrics.IngestionRecoveryMetrics;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

/**
 * IngestionReconcileService 单元测试（plan_21/21.5）。
 *
 * <p>验证 Reconciler 决策矩阵：
 *
 * <ul>
 *   <li>RAG READY → 修复 Knowledge 投影（REPAIRED）；
 *   <li>RAG FAILED 且可重试 → 自动 retry（RETRIED）；
 *   <li>RAG FAILED 但不可重试 → NO_ACTION；
 *   <li>RAG PROCESSING 超时 → 先 markTimedOut 再 retry（TIMED_OUT + RETRIED）；
 *   <li>RAG 不可用 → RAG_UNAVAILABLE 降级；
 *   <li>RAG Job 不存在 → DISPATCH_MISSING retry（RETRIED）；
 *   <li>已达 attempt 上限 → NO_ACTION；
 *   <li>retry CAS 冲突 → CONFLICT。
 * </ul>
 */
@DisplayName("IngestionReconcileService")
class IngestionReconcileServiceTest {

  private final DocumentDao documentDao = mock(DocumentDao.class);
  private final IngestionRetryService retryService = mock(IngestionRetryService.class);
  private final RagIngestionStatusClient statusClient = mock(RagIngestionStatusClient.class);
  private final IngestionRecoveryMetrics metrics = mock(IngestionRecoveryMetrics.class);
  private final ReconcilerProperties properties = testProperties();

  private IngestionReconcileService service;

  @BeforeEach
  void setUp() {
    properties.setPendingStaleThreshold(Duration.ofMinutes(2));
    properties.setProcessingStaleThreshold(Duration.ofMinutes(15));
    properties.setBatchSize(10);
    service =
        new IngestionReconcileService(
            documentDao, retryService, statusClient, metrics, new RetryPolicy(), properties);
  }

  private ReconcilerProperties testProperties() {
    ReconcilerProperties p = new ReconcilerProperties();
    p.setBatchSize(10);
    return p;
  }

  private DocumentEntity pendingDoc(long tenantId, long kbId, long docId, int attempt) {
    return seedEntity(tenantId, kbId, docId, "PENDING", 1L, attempt, null, null, 0L);
  }

  private DocumentEntity processingDoc(long tenantId, long kbId, long docId, int attempt) {
    return seedEntity(tenantId, kbId, docId, "PROCESSING", 1L, attempt, null, null, 0L);
  }

  private DocumentEntity failedDoc(
      long tenantId, long kbId, long docId, int attempt, String category) {
    return seedEntity(tenantId, kbId, docId, "FAILED", 1L, attempt, category, "msg", 0L);
  }

  private DocumentEntity seedEntity(
      long tenantId,
      long kbId,
      long docId,
      String status,
      long opVersion,
      int attempt,
      String category,
      String message,
      long version) {
    DocumentEntity e = DocumentEntity.create(kbId, tenantId, 1L, "doc.txt", "TXT", 5L, "abc");
    try {
      java.lang.reflect.Field docIdField = DocumentEntity.class.getDeclaredField("docId");
      docIdField.setAccessible(true);
      docIdField.set(e, docId);
      java.lang.reflect.Field statusField =
          DocumentEntity.class.getDeclaredField("ingestionStatus");
      statusField.setAccessible(true);
      statusField.set(e, status);
      java.lang.reflect.Field opVersionField =
          DocumentEntity.class.getDeclaredField("operationVersion");
      opVersionField.setAccessible(true);
      opVersionField.set(e, opVersion);
      java.lang.reflect.Field versionField = DocumentEntity.class.getDeclaredField("version");
      versionField.setAccessible(true);
      versionField.set(e, version);
      java.lang.reflect.Field attemptField =
          DocumentEntity.class.getDeclaredField("ingestionAttempt");
      attemptField.setAccessible(true);
      attemptField.set(e, attempt);
      if (category != null) {
        java.lang.reflect.Field categoryField =
            DocumentEntity.class.getDeclaredField("failureCategory");
        categoryField.setAccessible(true);
        categoryField.set(e, category);
      }
    } catch (ReflectiveOperationException ex) {
      throw new IllegalStateException(ex);
    }
    return e;
  }

  private void stubCandidates(DocumentEntity... docs) {
    when(documentDao.findStaleIngestionCandidates(any(), any(), anyInt()))
        .thenReturn(new PageImpl<>(List.of(docs), PageRequest.of(0, 10), docs.length));
  }

  private Instant now() {
    return Instant.parse("2026-06-28T12:00:00Z");
  }

  @Test
  @DisplayName("RAG READY 修复 Knowledge PENDING 投影（REPAIRED）")
  void ragReadyRepairsKnowledgeProjection() {
    DocumentEntity doc = pendingDoc(101L, 201L, 301L, 1);
    stubCandidates(doc);
    when(statusClient.getStatus(101L, 201L, 301L, 1L))
        .thenReturn(
            Optional.of(new RagIngestionStatus(1L, "READY", 1, 7001L, null, null, 1000L, 2000L)));

    ReconcileSummary summary = service.reconcileBatch(10, now());

    assertThat(summary.scanned()).isEqualTo(1);
    assertThat(summary.countBy(ReconcileOutcome.REPAIRED)).isEqualTo(1);
    verify(documentDao)
        .applyIngestionProjection(
            eq(301L),
            eq(101L),
            eq(201L),
            eq(1L),
            eq(0L),
            eq("READY"),
            anyInt(),
            eq(7001L),
            any(),
            any(),
            any(),
            any(),
            any());
    verify(metrics).reconcileRepaired();
    verify(retryService, never()).retry(anyLong(), anyLong(), anyLong(), anyLong());
  }

  @Test
  @DisplayName("RAG FAILED 且可重试 → 自动 retry（RETRIED）")
  void ragFailedRetryableTriggersAutoRetry() {
    DocumentEntity doc = failedDoc(101L, 201L, 302L, 1, "INDEX_TRANSIENT_FAILURE");
    stubCandidates(doc);
    when(statusClient.getStatus(101L, 201L, 302L, 1L))
        .thenReturn(
            Optional.of(
                new RagIngestionStatus(
                    1L, "FAILED", 1, 7002L, "INDEX_TRANSIENT_FAILURE", "transient", 1000L, 2000L)));
    when(retryService.retry(0L, 101L, 201L, 302L)).thenReturn(mock(DocumentResult.class));

    ReconcileSummary summary = service.reconcileBatch(10, now());

    assertThat(summary.countBy(ReconcileOutcome.RETRIED)).isEqualTo(1);
    verify(retryService).retry(0L, 101L, 201L, 302L);
    verify(metrics).reconcileRetried();
  }

  @Test
  @DisplayName("RAG FAILED 不可重试 → NO_ACTION，不创建新版本")
  void ragFailedNotRetryableNoAction() {
    DocumentEntity doc = failedDoc(101L, 201L, 303L, 1, "CHECKSUM_MISMATCH");
    stubCandidates(doc);
    when(statusClient.getStatus(101L, 201L, 303L, 1L))
        .thenReturn(
            Optional.of(
                new RagIngestionStatus(
                    1L, "FAILED", 1, 7003L, "CHECKSUM_MISMATCH", "checksum", 1000L, 2000L)));

    ReconcileSummary summary = service.reconcileBatch(10, now());

    assertThat(summary.countBy(ReconcileOutcome.NO_ACTION)).isEqualTo(1);
    verify(retryService, never()).retry(anyLong(), anyLong(), anyLong(), anyLong());
  }

  @Test
  @DisplayName("RAG FAILED 但已达 attempt 上限 → NO_ACTION")
  void ragFailedAttemptExhaustedNoAction() {
    DocumentEntity doc = failedDoc(101L, 201L, 304L, 3, "INDEX_TRANSIENT_FAILURE");
    stubCandidates(doc);
    when(statusClient.getStatus(101L, 201L, 304L, 1L))
        .thenReturn(
            Optional.of(
                new RagIngestionStatus(
                    1L, "FAILED", 3, 7004L, "INDEX_TRANSIENT_FAILURE", "transient", 1000L, 2000L)));

    ReconcileSummary summary = service.reconcileBatch(10, now());

    assertThat(summary.countBy(ReconcileOutcome.NO_ACTION)).isEqualTo(1);
    verify(retryService, never()).retry(anyLong(), anyLong(), anyLong(), anyLong());
  }

  @Test
  @DisplayName("RAG PROCESSING 超时 → markTimedOut 成功后 retry（TIMED_OUT 路径触发 RETRIED）")
  void ragProcessingStaleTimesOutThenRetries() {
    DocumentEntity doc = processingDoc(101L, 201L, 305L, 1);
    stubCandidates(doc);
    when(statusClient.getStatus(101L, 201L, 305L, 1L))
        .thenReturn(
            Optional.of(
                new RagIngestionStatus(1L, "PROCESSING", 1, 7005L, null, null, 1000L, null)));
    when(statusClient.markTimedOut(eq(101L), eq(201L), eq(305L), eq(1L), any()))
        .thenReturn(
            Optional.of(
                new RagIngestionStatus(
                    1L, "FAILED", 1, 7005L, "PROCESSING_TIMEOUT", "timed out", 1000L, 2000L)));
    when(retryService.retry(0L, 101L, 201L, 305L)).thenReturn(mock(DocumentResult.class));

    ReconcileSummary summary = service.reconcileBatch(10, now());

    assertThat(summary.countBy(ReconcileOutcome.RETRIED)).isEqualTo(1);
    verify(statusClient).markTimedOut(eq(101L), eq(201L), eq(305L), eq(1L), any());
    verify(metrics).reconcileTimedOut();
    verify(metrics).reconcileRetried();
  }

  @Test
  @DisplayName("RAG PROCESSING 未超时 → NO_ACTION")
  void ragProcessingNotStaleNoAction() {
    DocumentEntity doc = processingDoc(101L, 201L, 306L, 1);
    stubCandidates(doc);
    when(statusClient.getStatus(101L, 201L, 306L, 1L))
        .thenReturn(
            Optional.of(
                new RagIngestionStatus(1L, "PROCESSING", 1, 7006L, null, null, 1000L, null)));
    when(statusClient.markTimedOut(eq(101L), eq(201L), eq(306L), eq(1L), any()))
        .thenReturn(Optional.empty());

    ReconcileSummary summary = service.reconcileBatch(10, now());

    assertThat(summary.countBy(ReconcileOutcome.NO_ACTION)).isEqualTo(1);
    verify(retryService, never()).retry(anyLong(), anyLong(), anyLong(), anyLong());
  }

  @Test
  @DisplayName("RAG Status RPC 不可用 → RAG_UNAVAILABLE 降级")
  void ragUnavailableDegradesGracefully() {
    DocumentEntity doc = pendingDoc(101L, 201L, 307L, 1);
    stubCandidates(doc);
    when(statusClient.getStatus(101L, 201L, 307L, 1L))
        .thenThrow(new RuntimeException("rag unreachable"));

    ReconcileSummary summary = service.reconcileBatch(10, now());

    assertThat(summary.countBy(ReconcileOutcome.RAG_UNAVAILABLE)).isEqualTo(1);
    verify(metrics).reconcileRagUnavailable();
    verify(retryService, never()).retry(anyLong(), anyLong(), anyLong(), anyLong());
  }

  @Test
  @DisplayName("RAG Job 不存在 → DISPATCH_MISSING retry（RETRIED）")
  void ragJobMissingTriggersDispatchMissingRetry() {
    DocumentEntity doc = pendingDoc(101L, 201L, 308L, 1);
    stubCandidates(doc);
    when(statusClient.getStatus(101L, 201L, 308L, 1L)).thenReturn(Optional.empty());
    when(retryService.retry(0L, 101L, 201L, 308L)).thenReturn(mock(DocumentResult.class));

    ReconcileSummary summary = service.reconcileBatch(10, now());

    assertThat(summary.countBy(ReconcileOutcome.RETRIED)).isEqualTo(1);
    verify(retryService).retry(0L, 101L, 201L, 308L);
  }

  @Test
  @DisplayName("retry CAS 冲突 → CONFLICT")
  void retryCasConflictReportsConflict() {
    DocumentEntity doc = failedDoc(101L, 201L, 309L, 1, "PROCESSING_TIMEOUT");
    stubCandidates(doc);
    when(statusClient.getStatus(101L, 201L, 309L, 1L))
        .thenReturn(
            Optional.of(
                new RagIngestionStatus(
                    1L, "FAILED", 1, 7009L, "PROCESSING_TIMEOUT", "timeout", 1000L, 2000L)));
    when(retryService.retry(0L, 101L, 201L, 309L))
        .thenThrow(new VersionConflictException("CAS conflict"));

    ReconcileSummary summary = service.reconcileBatch(10, now());

    assertThat(summary.countBy(ReconcileOutcome.CONFLICT)).isEqualTo(1);
  }

  @Test
  @DisplayName("空候选 → scanned=0，无 RAG 调用")
  void noCandidatesNoRagCalls() {
    stubCandidates();

    ReconcileSummary summary = service.reconcileBatch(10, now());

    assertThat(summary.scanned()).isZero();
    verify(statusClient, never()).getStatus(anyLong(), anyLong(), anyLong(), anyLong());
    verify(metrics).reconcileScan();
  }
}
