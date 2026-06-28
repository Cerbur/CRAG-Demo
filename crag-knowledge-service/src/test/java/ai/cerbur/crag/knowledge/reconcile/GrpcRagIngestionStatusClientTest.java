package ai.cerbur.crag.knowledge.reconcile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.cerbur.crag.contracts.rag.v1.GetIngestionStatusRequest;
import ai.cerbur.crag.contracts.rag.v1.IngestionStatusServiceGrpc;
import ai.cerbur.crag.contracts.rag.v1.IngestionStatusView;
import ai.cerbur.crag.contracts.rag.v1.MarkTimedOutRequest;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * GrpcRagIngestionStatusClient 单元测试（plan_21/21.5）。
 *
 * <p>验证：
 *
 * <ul>
 *   <li>getStatus 正常返回 → 映射为 {@link RagIngestionStatus}；
 *   <li>getStatus NOT_FOUND → empty；
 *   <li>getStatus 其他错误 → 抛出；
 *   <li>markTimedOut 正常返回 → 映射为 FAILED 投影；
 *   <li>markTimedOut NOT_FOUND → empty。
 * </ul>
 *
 * <p>真实跨服务 gRPC（RAG 服务端 + 调用方身份 token + in-process server 装配）由 Docker HTTP 回归证明（21.13）； 本测试以
 * Mockito stub 验证客户端映射与错误处理逻辑。
 */
@DisplayName("GrpcRagIngestionStatusClient")
class GrpcRagIngestionStatusClientTest {

  private final IngestionStatusServiceGrpc.IngestionStatusServiceBlockingStub stub =
      mock(IngestionStatusServiceGrpc.IngestionStatusServiceBlockingStub.class);

  private GrpcRagIngestionStatusClient client() {
    return new GrpcRagIngestionStatusClient(stub);
  }

  @Test
  @DisplayName("getStatus 正常 → 映射 READY 投影")
  void getStatusMapsReadyView() {
    when(stub.getIngestionStatus(any(GetIngestionStatusRequest.class)))
        .thenReturn(
            IngestionStatusView.newBuilder()
                .setTenantId("101")
                .setKnowledgeBaseId("201")
                .setDocId("301")
                .setOperationVersion("1")
                .setStatus("READY")
                .setAttempt(1)
                .setJobId("7001")
                .setStartedAtEpochMillis(1000L)
                .setCompletedAtEpochMillis(2000L)
                .build());

    Optional<RagIngestionStatus> result = client().getStatus(101L, 201L, 301L, 1L);

    assertThat(result).isPresent();
    assertThat(result.get().status()).isEqualTo("READY");
    assertThat(result.get().operationVersion()).isEqualTo(1L);
    assertThat(result.get().jobId()).isEqualTo(7001L);
    assertThat(result.get().startedAtEpochMillis()).isEqualTo(1000L);
    assertThat(result.get().completedAtEpochMillis()).isEqualTo(2000L);
    assertThat(result.get().failureCategory()).isNull();
  }

  @Test
  @DisplayName("getStatus FAILED → 映射失败分类与消息")
  void getStatusMapsFailedView() {
    when(stub.getIngestionStatus(any(GetIngestionStatusRequest.class)))
        .thenReturn(
            IngestionStatusView.newBuilder()
                .setTenantId("101")
                .setKnowledgeBaseId("201")
                .setDocId("302")
                .setOperationVersion("1")
                .setStatus("FAILED")
                .setAttempt(2)
                .setJobId("7002")
                .setFailureCategory("INDEX_TRANSIENT_FAILURE")
                .setFailureMessage("transient")
                .build());

    Optional<RagIngestionStatus> result = client().getStatus(101L, 201L, 302L, 1L);

    assertThat(result).isPresent();
    assertThat(result.get().status()).isEqualTo("FAILED");
    assertThat(result.get().failureCategory()).isEqualTo("INDEX_TRANSIENT_FAILURE");
    assertThat(result.get().failureMessage()).isEqualTo("transient");
    assertThat(result.get().attempt()).isEqualTo(2);
  }

  @Test
  @DisplayName("getStatus NOT_FOUND → empty")
  void getStatusNotFoundReturnsEmpty() {
    when(stub.getIngestionStatus(any(GetIngestionStatusRequest.class)))
        .thenThrow(Status.NOT_FOUND.withDescription("not found").asRuntimeException());

    Optional<RagIngestionStatus> result = client().getStatus(101L, 201L, 303L, 1L);

    assertThat(result).isEmpty();
  }

  @Test
  @DisplayName("getStatus UNAVAILABLE → 抛出（由调用方降级）")
  void getStatusUnavailablePropagates() {
    when(stub.getIngestionStatus(any(GetIngestionStatusRequest.class)))
        .thenThrow(Status.UNAVAILABLE.withDescription("rag down").asRuntimeException());

    assertThatThrownBy(() -> client().getStatus(101L, 201L, 304L, 1L))
        .isInstanceOf(StatusRuntimeException.class);
  }

  @Test
  @DisplayName("markTimedOut 正常 → 映射 FAILED 超时投影")
  void markTimedOutMapsTimedOutView() {
    when(stub.markTimedOut(any(MarkTimedOutRequest.class)))
        .thenReturn(
            IngestionStatusView.newBuilder()
                .setTenantId("101")
                .setKnowledgeBaseId("201")
                .setDocId("305")
                .setOperationVersion("1")
                .setStatus("FAILED")
                .setAttempt(1)
                .setJobId("7005")
                .setFailureCategory("PROCESSING_TIMEOUT")
                .setFailureMessage("timed out")
                .build());

    Optional<RagIngestionStatus> result =
        client().markTimedOut(101L, 201L, 305L, 1L, Instant.parse("2026-06-28T11:00:00Z"));

    assertThat(result).isPresent();
    assertThat(result.get().status()).isEqualTo("FAILED");
    assertThat(result.get().failureCategory()).isEqualTo("PROCESSING_TIMEOUT");
  }

  @Test
  @DisplayName("markTimedOut NOT_FOUND → empty")
  void markTimedOutNotFoundReturnsEmpty() {
    when(stub.markTimedOut(any(MarkTimedOutRequest.class)))
        .thenThrow(Status.NOT_FOUND.withDescription("not stale").asRuntimeException());

    Optional<RagIngestionStatus> result =
        client().markTimedOut(101L, 201L, 306L, 1L, Instant.parse("2026-06-28T11:00:00Z"));

    assertThat(result).isEmpty();
  }
}
