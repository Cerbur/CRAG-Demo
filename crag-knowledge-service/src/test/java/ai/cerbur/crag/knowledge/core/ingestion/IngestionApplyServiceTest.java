package ai.cerbur.crag.knowledge.core.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.cerbur.crag.knowledge.dao.DocumentDao;
import ai.cerbur.crag.knowledge.dao.VersionConflictException;
import ai.cerbur.crag.knowledge.dao.entity.DocumentEntity;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * IngestionApplyService 单元测试（plan_21/21.3）。
 *
 * <p>验证：合法迁移写库并 APPLIED；旧版本 ACK 不写库；重复终态 ACK 不写库；矛盾终态 ACK 不写库； Tenant/KB/doc 不一致 REJECTED（DLQ）；CAS
 * 失败 RETRYABLE；文档不存在 RETRYABLE；瞬时异常 RETRYABLE。
 */
@DisplayName("IngestionApplyService")
class IngestionApplyServiceTest {

  private final DocumentDao documentDao = mock(DocumentDao.class);

  private IngestionApplyService service() {
    return new IngestionApplyService(documentDao);
  }

  private DocumentEntity doc(long tenantId, long kbId, long docId, String status, long opVersion) {
    DocumentEntity e = DocumentEntity.create(kbId, tenantId, 1L, "doc.txt", "TXT", 5L, "abc");
    when(documentDao.findByDocIdAndTenant(docId, tenantId))
        .thenReturn(Optional.of(seedEntity(tenantId, kbId, docId, status, opVersion)));
    return e;
  }

  private DocumentEntity seedEntity(
      long tenantId, long kbId, long docId, String status, long opVersion) {
    // 通过反射设置 docId 与状态以模拟数据库行；测试专用。
    DocumentEntity e = DocumentEntity.create(kbId, tenantId, 1L, "doc.txt", "TXT", 5L, "abc");
    java.lang.reflect.Field docIdField;
    java.lang.reflect.Field statusField;
    java.lang.reflect.Field opVersionField;
    try {
      docIdField = DocumentEntity.class.getDeclaredField("docId");
      docIdField.setAccessible(true);
      docIdField.set(e, docId);
      statusField = DocumentEntity.class.getDeclaredField("ingestionStatus");
      statusField.setAccessible(true);
      statusField.set(e, status);
      opVersionField = DocumentEntity.class.getDeclaredField("operationVersion");
      opVersionField.setAccessible(true);
      opVersionField.set(e, opVersion);
    } catch (ReflectiveOperationException ex) {
      throw new IllegalStateException(ex);
    }
    return e;
  }

  private IngestionStatusEvent event(
      long tenantId, long kbId, long docId, long opVersion, IngestionStatus target) {
    return new IngestionStatusEvent(
        tenantId,
        kbId,
        docId,
        opVersion,
        target == IngestionStatus.PROCESSING ? 1 : null,
        9001L,
        target,
        target == IngestionStatus.FAILED ? "INDEX_TRANSIENT_FAILURE" : null,
        target == IngestionStatus.FAILED ? "transient" : null,
        target == IngestionStatus.PROCESSING ? LocalDateTime.now() : null,
        target == IngestionStatus.READY || target == IngestionStatus.FAILED
            ? LocalDateTime.now()
            : null);
  }

  @Test
  @DisplayName("PENDING + PROCESSING 事件 → APPLIED，调用 DAO applyIngestionProjection")
  void pendingToProcessingApplied() {
    doc(1L, 10L, 100L, "PENDING", 1L);

    IngestionApplyResult result =
        service().apply(event(1L, 10L, 100L, 1L, IngestionStatus.PROCESSING));

    assertThat(result.decision()).isEqualTo(IngestionApplyResult.Decision.APPLIED);
    verify(documentDao, times(1))
        .applyIngestionProjection(
            eq(100L),
            eq(1L),
            eq(10L),
            eq(1L),
            eq(0L),
            eq("PROCESSING"),
            anyInt(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any());
  }

  @Test
  @DisplayName("PROCESSING + READY 事件 → APPLIED")
  void processingToReadyApplied() {
    doc(1L, 10L, 101L, "PROCESSING", 1L);

    IngestionApplyResult result = service().apply(event(1L, 10L, 101L, 1L, IngestionStatus.READY));

    assertThat(result.decision()).isEqualTo(IngestionApplyResult.Decision.APPLIED);
    verify(documentDao, times(1))
        .applyIngestionProjection(
            eq(101L),
            eq(1L),
            eq(10L),
            eq(1L),
            eq(0L),
            eq("READY"),
            anyInt(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any());
  }

  @Test
  @DisplayName("旧 operationVersion 事件 → ACKNOWLEDGED，不写库")
  void oldOperationVersionAcknowledged() {
    doc(1L, 10L, 102L, "READY", 3L);

    IngestionApplyResult result = service().apply(event(1L, 10L, 102L, 1L, IngestionStatus.READY));

    assertThat(result.decision()).isEqualTo(IngestionApplyResult.Decision.ACKNOWLEDGED);
    verify(documentDao, never())
        .applyIngestionProjection(
            anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), any(), anyInt(), any(), any(),
            any(), any(), any(), any());
  }

  @Test
  @DisplayName("高 operationVersion 事件 → REJECTED（RAG 不应超前 Knowledge），DLQ")
  void futureOperationVersionRejected() {
    doc(1L, 10L, 103L, "PROCESSING", 1L);

    IngestionApplyResult result = service().apply(event(1L, 10L, 103L, 5L, IngestionStatus.READY));

    assertThat(result.decision()).isEqualTo(IngestionApplyResult.Decision.REJECTED);
    verify(documentDao, never())
        .applyIngestionProjection(
            anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), any(), anyInt(), any(), any(),
            any(), any(), any(), any());
  }

  @Test
  @DisplayName("重复 READY 终态 → ACKNOWLEDGED，不写库")
  void duplicateReadyAcknowledged() {
    doc(1L, 10L, 104L, "READY", 1L);

    IngestionApplyResult result = service().apply(event(1L, 10L, 104L, 1L, IngestionStatus.READY));

    assertThat(result.decision()).isEqualTo(IngestionApplyResult.Decision.ACKNOWLEDGED);
    verify(documentDao, never())
        .applyIngestionProjection(
            anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), any(), anyInt(), any(), any(),
            any(), any(), any(), any());
  }

  @Test
  @DisplayName("矛盾终态 READY→FAILED → ACKNOWLEDGED，不覆盖事实")
  void contradictoryTerminalAcknowledged() {
    doc(1L, 10L, 105L, "READY", 1L);

    IngestionApplyResult result = service().apply(event(1L, 10L, 105L, 1L, IngestionStatus.FAILED));

    assertThat(result.decision()).isEqualTo(IngestionApplyResult.Decision.ACKNOWLEDGED);
    verify(documentDao, never())
        .applyIngestionProjection(
            anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), any(), anyInt(), any(), any(),
            any(), any(), any(), any());
  }

  @Test
  @DisplayName("Tenant 不一致 → REJECTED（DLQ）")
  void tenantMismatchRejected() {
    // 文档实际属于 tenant=2，事件声明 tenant=1：findByDocIdAndTenant(doc, 1) 返回空 → 视为不一致。
    when(documentDao.findByDocIdAndTenant(106L, 1L)).thenReturn(Optional.empty());

    IngestionApplyResult result = service().apply(event(1L, 10L, 106L, 1L, IngestionStatus.READY));

    assertThat(result.decision()).isEqualTo(IngestionApplyResult.Decision.REJECTED);
  }

  @Test
  @DisplayName("knowledgeBase 不一致 → REJECTED（DLQ）")
  void knowledgeBaseMismatchRejected() {
    // 文档存在于声明的 tenant，但 kb 不匹配。
    when(documentDao.findByDocIdAndTenant(107L, 1L))
        .thenReturn(Optional.of(seedEntity(1L, 999L, 107L, "PENDING", 1L)));

    IngestionApplyResult result =
        service().apply(event(1L, 10L, 107L, 1L, IngestionStatus.PROCESSING));

    assertThat(result.decision()).isEqualTo(IngestionApplyResult.Decision.REJECTED);
    verify(documentDao, never())
        .applyIngestionProjection(
            anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), any(), anyInt(), any(), any(),
            any(), any(), any(), any());
  }

  @Test
  @DisplayName("文档在声明的 tenant 下不存在 → REJECTED（docId 全局唯一，缺失视为 tenant/doc 归属不一致，安全 DLQ）")
  void documentMissingRejected() {
    when(documentDao.findByDocIdAndTenant(108L, 1L)).thenReturn(Optional.empty());

    IngestionApplyResult result =
        service().apply(event(1L, 10L, 108L, 1L, IngestionStatus.PROCESSING));

    assertThat(result.decision()).isEqualTo(IngestionApplyResult.Decision.REJECTED);
  }

  @Test
  @DisplayName("CAS 冲突 → RETRYABLE")
  void casConflictRetryable() {
    doc(1L, 10L, 109L, "PENDING", 1L);
    doThrow(new VersionConflictException("stale"))
        .when(documentDao)
        .applyIngestionProjection(
            anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), any(), anyInt(), any(), any(),
            any(), any(), any(), any());

    IngestionApplyResult result =
        service().apply(event(1L, 10L, 109L, 1L, IngestionStatus.PROCESSING));

    assertThat(result.decision()).isEqualTo(IngestionApplyResult.Decision.RETRYABLE);
  }

  @Test
  @DisplayName("瞬时异常 → RETRYABLE")
  void transientFailureRetryable() {
    doc(1L, 10L, 110L, "PENDING", 1L);
    doThrow(new RuntimeException("db unavailable"))
        .when(documentDao)
        .applyIngestionProjection(
            anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), any(), anyInt(), any(), any(),
            any(), any(), any(), any());

    IngestionApplyResult result =
        service().apply(event(1L, 10L, 110L, 1L, IngestionStatus.PROCESSING));

    assertThat(result.decision()).isEqualTo(IngestionApplyResult.Decision.RETRYABLE);
  }
}
