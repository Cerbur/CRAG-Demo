package ai.cerbur.crag.knowledge.core.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.cerbur.crag.knowledge.core.document.DocumentResult;
import ai.cerbur.crag.knowledge.dao.DocumentDao;
import ai.cerbur.crag.knowledge.dao.VersionConflictException;
import ai.cerbur.crag.knowledge.dao.entity.DocumentEntity;
import ai.cerbur.crag.knowledge.metrics.IngestionRecoveryMetrics;
import ai.cerbur.crag.knowledge.producer.DocUploadedOutboxWriter;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;

/**
 * IngestionRetryService 并发测试（plan_21/21.5）。
 *
 * <p>验证并发 retry 只允许一个新版本成功：多个线程同时 retry 同一 FAILED 文档时，CAS 保证只有一个调用成功推进 operationVersion， 其余线程抛出
 * {@link VersionConflictException}。Outbox 只被成功的那次调用写入一次。
 */
@DisplayName("IngestionRetryService 并发")
class IngestionRetryConcurrencyTest {

  @Test
  @DisplayName("并发 retry 同一 FAILED 文档：CAS 只允许一个成功，其余冲突")
  void concurrentRetryOnlyOneSucceeds() throws Exception {
    DocumentDao documentDao = mock(DocumentDao.class);
    DocUploadedOutboxWriter outboxWriter = mock(DocUploadedOutboxWriter.class);
    IngestionRecoveryMetrics metrics = mock(IngestionRecoveryMetrics.class);

    DocumentEntity failed = seedFailed(101L, 201L, 301L, 1, "INDEX_TRANSIENT_FAILURE");
    when(documentDao.findByDocIdAndTenant(301L, 101L)).thenReturn(Optional.of(failed));

    // 模拟 CAS：只允许第一次调用成功，其余返回 0 rows（DAO 抛 VersionConflictException）。
    AtomicInteger casAttempts = new AtomicInteger(0);
    when(documentDao.retryIngestion(
            anyLong(),
            anyLong(),
            anyLong(),
            anyLong(),
            anyLong(),
            org.mockito.ArgumentMatchers.anyInt(),
            anyLong()))
        .thenAnswer(
            (InvocationOnMock inv) -> {
              if (casAttempts.incrementAndGet() == 1) {
                return 1; // 第一个线程成功
              }
              throw new VersionConflictException("CAS conflict");
            });

    IngestionRetryService service =
        new IngestionRetryService(documentDao, outboxWriter, metrics, new RetryPolicy());

    int threads = 8;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger successes = new AtomicInteger(0);
    AtomicInteger conflicts = new AtomicInteger(0);
    CountDownLatch done = new CountDownLatch(threads);
    for (int i = 0; i < threads; i++) {
      pool.submit(
          () -> {
            try {
              start.await();
              service.retry(501L, 101L, 201L, 301L);
              successes.incrementAndGet();
            } catch (VersionConflictException e) {
              conflicts.incrementAndGet();
            } catch (Exception e) {
              // 其他异常视为失败
            } finally {
              done.countDown();
            }
          });
    }
    start.countDown();
    boolean finished = done.await(10, TimeUnit.SECONDS);
    pool.shutdownNow();
    assertThat(finished).as("all threads must finish").isTrue();

    assertThat(successes.get()).as("only one retry should succeed").isEqualTo(1);
    assertThat(conflicts.get()).as("rest must conflict").isEqualTo(threads - 1);
    // Outbox 只被成功的那次写一次（注意：成功路径会 re-read，mock 仍返回 failed，
    // 实际成功线程会调 outboxWriter.write 一次）。
    verify(outboxWriter, times(1)).write(any(DocumentResult.class));
    verify(metrics, times(1)).retryIssued();
    verify(metrics, atLeast(threads - 1)).retryConflict();
  }

  private DocumentEntity seedFailed(
      long tenantId, long kbId, long docId, int attempt, String category) {
    DocumentEntity e = DocumentEntity.create(kbId, tenantId, 1L, "doc.txt", "TXT", 5L, "abc");
    try {
      java.lang.reflect.Field docIdField = DocumentEntity.class.getDeclaredField("docId");
      docIdField.setAccessible(true);
      docIdField.set(e, docId);
      java.lang.reflect.Field statusField =
          DocumentEntity.class.getDeclaredField("ingestionStatus");
      statusField.setAccessible(true);
      statusField.set(e, "FAILED");
      java.lang.reflect.Field opVersionField =
          DocumentEntity.class.getDeclaredField("operationVersion");
      opVersionField.setAccessible(true);
      opVersionField.set(e, 1L);
      java.lang.reflect.Field versionField = DocumentEntity.class.getDeclaredField("version");
      versionField.setAccessible(true);
      versionField.set(e, 0L);
      java.lang.reflect.Field attemptField =
          DocumentEntity.class.getDeclaredField("ingestionAttempt");
      attemptField.setAccessible(true);
      attemptField.set(e, attempt);
      java.lang.reflect.Field categoryField =
          DocumentEntity.class.getDeclaredField("failureCategory");
      categoryField.setAccessible(true);
      categoryField.set(e, category);
    } catch (ReflectiveOperationException ex) {
      throw new IllegalStateException(ex);
    }
    return e;
  }
}
