package ai.cerbur.crag.knowledge.core.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.cerbur.crag.knowledge.core.document.DocumentResult;
import ai.cerbur.crag.knowledge.dao.DocumentDao;
import ai.cerbur.crag.knowledge.dao.VersionConflictException;
import ai.cerbur.crag.knowledge.dao.entity.DocumentEntity;
import ai.cerbur.crag.knowledge.metrics.IngestionRecoveryMetrics;
import ai.cerbur.crag.knowledge.producer.DocUploadedOutboxWriter;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * IngestionRetryService 单元测试（plan_21/21.5）。
 *
 * <p>验证：
 *
 * <ul>
 *   <li>FAILED 且可重试分类 → CAS 递增 operationVersion/attempt、清失败字段、同事务写 DOC_UPLOADED；
 *   <li>FAILED 但不可重试分类 → 抛出 RetryNotAllowedException，不写库不发事件；
 *   <li>非 FAILED 文档 → 抛出 RetryNotAllowedException；
 *   <li>已达 attempt 上限 → 抛出 RetryNotAllowedException；
 *   <li>跨租户/KB 文档缺失 → 抛出 RetryNotAllowedException；
 *   <li>CAS 冲突 → 抛出 VersionConflictException，metrics 记录冲突。
 * </ul>
 */
@DisplayName("IngestionRetryService")
class IngestionRetryServiceTest {

  private final DocumentDao documentDao = mock(DocumentDao.class);
  private final DocUploadedOutboxWriter outboxWriter = mock(DocUploadedOutboxWriter.class);
  private final IngestionRecoveryMetrics metrics = mock(IngestionRecoveryMetrics.class);

  private IngestionRetryService service() {
    return new IngestionRetryService(documentDao, outboxWriter, metrics, new RetryPolicy());
  }

  private DocumentEntity failedDoc(
      long tenantId, long kbId, long docId, int attempt, String category) {
    DocumentEntity e =
        seedEntity(tenantId, kbId, docId, "FAILED", 1L, attempt, category, "msg", 0L);
    when(documentDao.findByDocIdAndTenant(docId, tenantId)).thenReturn(Optional.of(e));
    return e;
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
      java.lang.reflect.Field categoryField =
          DocumentEntity.class.getDeclaredField("failureCategory");
      categoryField.setAccessible(true);
      categoryField.set(e, category);
      java.lang.reflect.Field messageField =
          DocumentEntity.class.getDeclaredField("failureMessage");
      messageField.setAccessible(true);
      messageField.set(e, message);
    } catch (ReflectiveOperationException ex) {
      throw new IllegalStateException(ex);
    }
    return e;
  }

  @Test
  @DisplayName("可重试 FAILED → CAS 递增 operationVersion/attempt，清失败字段，写 DOC_UPLOADED")
  void retryableFailedAdvancesVersionAndWritesOutbox() {
    DocumentEntity failed = failedDoc(101L, 201L, 301L, 1, "INDEX_TRANSIENT_FAILURE");
    when(documentDao.retryIngestion(eq(301L), eq(101L), eq(201L), eq(1L), eq(0L), eq(2), eq(2L)))
        .thenReturn(1);
    // CAS 成功后 re-read 返回新版本（PENDING、opVersion=2、attempt=2、version=1）。
    DocumentEntity refreshed = seedEntity(101L, 201L, 301L, "PENDING", 2L, 2, null, null, 1L);
    when(documentDao.findByDocIdAndTenant(301L, 101L))
        .thenReturn(Optional.of(failed))
        .thenReturn(Optional.of(refreshed));

    DocumentResult result = service().retry(501L, 101L, 201L, 301L);

    assertThat(result.docId()).isEqualTo(301L);
    assertThat(result.operationVersion()).isEqualTo(2L);
    assertThat(result.ingestionStatus()).isEqualTo("PENDING");
    assertThat(result.ingestionAttempt()).isEqualTo(2);
    verify(documentDao).retryIngestion(eq(301L), eq(101L), eq(201L), eq(1L), eq(0L), eq(2), eq(2L));
    verify(outboxWriter).write(any(DocumentResult.class));
    verify(metrics).retryIssued();
  }

  @Test
  @DisplayName("不可重试分类 → RetryNotAllowedException，不写库不发事件")
  void deterministicFailureThrowsRetryNotAllowed() {
    failedDoc(101L, 201L, 302L, 1, "CHECKSUM_MISMATCH");

    assertThatThrownBy(() -> service().retry(501L, 101L, 201L, 302L))
        .isInstanceOf(RetryNotAllowedException.class);

    verify(documentDao, never())
        .retryIngestion(
            anyLong(),
            anyLong(),
            anyLong(),
            anyLong(),
            anyLong(),
            org.mockito.ArgumentMatchers.anyInt(),
            anyLong());
    verify(outboxWriter, never()).write(any());
    verify(metrics, never()).retryIssued();
  }

  @Test
  @DisplayName("非 FAILED 文档 → RetryNotAllowedException")
  void nonFailedThrowsRetryNotAllowed() {
    DocumentEntity ready = seedEntity(101L, 201L, 303L, "READY", 1L, 1, null, null, 0L);
    when(documentDao.findByDocIdAndTenant(303L, 101L)).thenReturn(Optional.of(ready));

    assertThatThrownBy(() -> service().retry(501L, 101L, 201L, 303L))
        .isInstanceOf(RetryNotAllowedException.class);
  }

  @Test
  @DisplayName("已达 attempt 上限 → RetryNotAllowedException")
  void attemptExhaustedThrowsRetryNotAllowed() {
    failedDoc(101L, 201L, 304L, 3, "INDEX_TRANSIENT_FAILURE");

    assertThatThrownBy(() -> service().retry(501L, 101L, 201L, 304L))
        .isInstanceOf(RetryNotAllowedException.class);
  }

  @Test
  @DisplayName("文档不存在 → RetryNotAllowedException")
  void missingDocThrowsRetryNotAllowed() {
    when(documentDao.findByDocIdAndTenant(305L, 101L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().retry(501L, 101L, 201L, 305L))
        .isInstanceOf(RetryNotAllowedException.class);
  }

  @Test
  @DisplayName("CAS 冲突 → VersionConflictException，metrics 记录冲突")
  void casConflictPropagatesAndRecordsConflict() {
    failedDoc(101L, 201L, 306L, 1, "PROCESSING_TIMEOUT");
    doThrow(new VersionConflictException("CAS conflict"))
        .when(documentDao)
        .retryIngestion(eq(306L), eq(101L), eq(201L), eq(1L), eq(0L), eq(2), eq(2L));

    assertThatThrownBy(() -> service().retry(501L, 101L, 201L, 306L))
        .isInstanceOf(VersionConflictException.class);

    verify(metrics).retryConflict();
    verify(outboxWriter, never()).write(any());
  }

  @Test
  @DisplayName("KB 不匹配 → RetryNotAllowedException")
  void kbMismatchThrowsRetryNotAllowed() {
    failedDoc(101L, 999L, 307L, 1, "INDEX_TRANSIENT_FAILURE");

    assertThatThrownBy(() -> service().retry(501L, 101L, 201L, 307L))
        .isInstanceOf(RetryNotAllowedException.class);
  }
}
