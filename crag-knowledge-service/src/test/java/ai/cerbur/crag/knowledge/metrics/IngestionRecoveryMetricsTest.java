package ai.cerbur.crag.knowledge.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * IngestionRecoveryMetrics 单元测试（plan_21/21.5）。
 *
 * <p>验证 retry/timeout/reconcile 计数器正确注册并递增。
 */
@DisplayName("IngestionRecoveryMetrics")
class IngestionRecoveryMetricsTest {

  @Test
  @DisplayName("retryIssued 递增 knowledge.ingestion.retry.issued 计数器")
  void retryIssuedIncrementsCounter() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    IngestionRecoveryMetrics metrics = new IngestionRecoveryMetrics();
    org.springframework.test.util.ReflectionTestUtils.setField(metrics, "registry", registry);

    metrics.retryIssued();
    metrics.retryIssued();

    assertThat(registry.counter("knowledge.ingestion.retry.issued").count()).isEqualTo(2.0);
  }

  @Test
  @DisplayName("reconcileCandidates 按数量递增")
  void reconcileCandidatesIncrementsByCount() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    IngestionRecoveryMetrics metrics = new IngestionRecoveryMetrics();
    org.springframework.test.util.ReflectionTestUtils.setField(metrics, "registry", registry);

    metrics.reconcileCandidates(5);

    assertThat(registry.counter("knowledge.ingestion.reconcile.candidates").count()).isEqualTo(5.0);
  }

  @Test
  @DisplayName("所有 reconcile 计数器可独立递增")
  void allReconcileCountersIncrement() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    IngestionRecoveryMetrics metrics = new IngestionRecoveryMetrics();
    org.springframework.test.util.ReflectionTestUtils.setField(metrics, "registry", registry);

    metrics.reconcileScan();
    metrics.reconcileRepaired();
    metrics.reconcileTimedOut();
    metrics.reconcileRetried();
    metrics.reconcileRagUnavailable();
    metrics.retryConflict();
    metrics.retryRejected();

    assertThat(registry.counter("knowledge.ingestion.reconcile.scan").count()).isEqualTo(1.0);
    assertThat(registry.counter("knowledge.ingestion.reconcile.repaired").count()).isEqualTo(1.0);
    assertThat(registry.counter("knowledge.ingestion.reconcile.timed_out").count()).isEqualTo(1.0);
    assertThat(registry.counter("knowledge.ingestion.reconcile.retried").count()).isEqualTo(1.0);
    assertThat(registry.counter("knowledge.ingestion.reconcile.rag_unavailable").count())
        .isEqualTo(1.0);
    assertThat(registry.counter("knowledge.ingestion.retry.conflict").count()).isEqualTo(1.0);
    assertThat(registry.counter("knowledge.ingestion.retry.rejected").count()).isEqualTo(1.0);
  }
}
