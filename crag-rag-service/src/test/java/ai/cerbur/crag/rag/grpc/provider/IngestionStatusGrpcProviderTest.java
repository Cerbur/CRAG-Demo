package ai.cerbur.crag.rag.grpc.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.cerbur.crag.contracts.rag.v1.GetIngestionStatusRequest;
import ai.cerbur.crag.contracts.rag.v1.IngestionStatusView;
import ai.cerbur.crag.contracts.rag.v1.MarkTimedOutRequest;
import ai.cerbur.crag.ingestion.head.IngestionHeadService;
import ai.cerbur.crag.ingestion.head.IngestionStatusResult;
import ai.cerbur.crag.rag.grpc.security.RagRpcAuthorizer;
import ai.cerbur.crag.storage.entity.IngestionJobStatus;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** IngestionStatusGrpcProvider 单元测试（Plan 21.4）：验证调用方授权（仅 Knowledge）、十进制 ID 解析、状态投影映射与 超时终态化. */
@DisplayName("IngestionStatusGrpcProvider 调用方授权与状态投影")
@ExtendWith(MockitoExtension.class)
class IngestionStatusGrpcProviderTest {

  @Mock private IngestionHeadService ingestionHeadService;
  @Mock private RagRpcAuthorizer authorizer;
  @Mock private StreamObserver<IngestionStatusView> responseObserver;

  @InjectMocks private IngestionStatusGrpcProvider provider;

  private IngestionStatusResult sampleResult() {
    return new IngestionStatusResult(
        101L,
        200L,
        3001L,
        1L,
        IngestionJobStatus.READY,
        0,
        42L,
        null,
        null,
        LocalDateTime.of(2026, 6, 28, 10, 0, 0),
        LocalDateTime.of(2026, 6, 28, 10, 1, 0));
  }

  @Test
  @DisplayName("Knowledge 查询当前状态 → 返回 IngestionStatusView")
  void knowledgeGetReturnsStatusView() {
    when(ingestionHeadService.get(anyLong(), anyLong(), anyLong()))
        .thenReturn(
            Optional.of(new ai.cerbur.crag.storage.result.IngestionHead(200L, 3001L, 1L, 0L)));
    when(ingestionHeadService.currentJobStatus(anyLong(), anyLong(), anyLong()))
        .thenReturn(Optional.of(sampleResult()));
    GetIngestionStatusRequest request =
        GetIngestionStatusRequest.newBuilder()
            .setTenantId("101")
            .setKnowledgeBaseId("200")
            .setDocId("3001")
            .setOperationVersion("1")
            .build();

    provider.getIngestionStatus(request, responseObserver);

    verify(authorizer).requireKnowledgeService();
    ArgumentCaptor<IngestionStatusView> captor = ArgumentCaptor.forClass(IngestionStatusView.class);
    verify(responseObserver).onNext(captor.capture());
    verify(responseObserver).onCompleted();
    IngestionStatusView view = captor.getValue();
    assertThat(view.getTenantId()).isEqualTo("101");
    assertThat(view.getKnowledgeBaseId()).isEqualTo("200");
    assertThat(view.getDocId()).isEqualTo("3001");
    assertThat(view.getOperationVersion()).isEqualTo("1");
    assertThat(view.getStatus()).isEqualTo("READY");
    assertThat(view.getJobId()).isEqualTo("42");
  }

  @Test
  @DisplayName("非 Knowledge 调用 → PERMISSION_DENIED")
  void nonKnowledgeRejected() {
    doThrow(Status.PERMISSION_DENIED.withDescription("caller not allowed").asRuntimeException())
        .when(authorizer)
        .requireKnowledgeService();
    GetIngestionStatusRequest request =
        GetIngestionStatusRequest.newBuilder()
            .setTenantId("1")
            .setKnowledgeBaseId("2")
            .setDocId("3")
            .setOperationVersion("4")
            .build();

    provider.getIngestionStatus(request, responseObserver);

    ArgumentCaptor<Throwable> errorCaptor = ArgumentCaptor.forClass(Throwable.class);
    verify(responseObserver).onError(errorCaptor.capture());
    assertThat(errorCaptor.getValue())
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            sre -> assertThat(sre.getStatus().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED));
  }

  @Test
  @DisplayName("head 不存在 → NOT_FOUND")
  void missingHeadReturnsNotFound() {
    when(ingestionHeadService.get(anyLong(), anyLong(), anyLong())).thenReturn(Optional.empty());
    GetIngestionStatusRequest request =
        GetIngestionStatusRequest.newBuilder()
            .setTenantId("1")
            .setKnowledgeBaseId("2")
            .setDocId("3")
            .setOperationVersion("4")
            .build();

    provider.getIngestionStatus(request, responseObserver);

    ArgumentCaptor<Throwable> errorCaptor = ArgumentCaptor.forClass(Throwable.class);
    verify(responseObserver).onError(errorCaptor.capture());
    assertThat(errorCaptor.getValue())
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            sre -> assertThat(sre.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND));
  }

  @Test
  @DisplayName("Knowledge markTimedOut → 终态化后返回 FAILED 视图")
  void knowledgeMarkTimedOutReturnsFailedView() {
    IngestionStatusResult failed =
        new IngestionStatusResult(
            101L,
            200L,
            3001L,
            1L,
            IngestionJobStatus.FAILED,
            0,
            42L,
            "PROCESSING_TIMEOUT",
            "ingestion job exceeded processing budget",
            LocalDateTime.of(2026, 6, 28, 10, 0, 0),
            LocalDateTime.of(2026, 6, 28, 10, 5, 0));
    when(ingestionHeadService.markTimedOut(anyLong(), anyLong(), anyLong(), anyLong(), any()))
        .thenReturn(Optional.of(failed));
    MarkTimedOutRequest request =
        MarkTimedOutRequest.newBuilder()
            .setTenantId("101")
            .setKnowledgeBaseId("200")
            .setDocId("3001")
            .setOperationVersion("1")
            .setStaleBeforeEpochMillis(1_000L)
            .build();

    provider.markTimedOut(request, responseObserver);

    verify(authorizer).requireKnowledgeService();
    ArgumentCaptor<IngestionStatusView> captor = ArgumentCaptor.forClass(IngestionStatusView.class);
    verify(responseObserver).onNext(captor.capture());
    verify(responseObserver).onCompleted();
    IngestionStatusView view = captor.getValue();
    assertThat(view.getStatus()).isEqualTo("FAILED");
    assertThat(view.getFailureCategory()).isEqualTo("PROCESSING_TIMEOUT");
    assertThat(view.getFailureMessage()).isEqualTo("ingestion job exceeded processing budget");
  }

  @Test
  @DisplayName("markTimedOut Job 不存在或未超时 → NOT_FOUND")
  void markTimedOutNotFound() {
    when(ingestionHeadService.markTimedOut(anyLong(), anyLong(), anyLong(), anyLong(), any()))
        .thenReturn(Optional.empty());
    MarkTimedOutRequest request =
        MarkTimedOutRequest.newBuilder()
            .setTenantId("1")
            .setKnowledgeBaseId("2")
            .setDocId("3")
            .setOperationVersion("4")
            .setStaleBeforeEpochMillis(1L)
            .build();

    provider.markTimedOut(request, responseObserver);

    ArgumentCaptor<Throwable> errorCaptor = ArgumentCaptor.forClass(Throwable.class);
    verify(responseObserver).onError(errorCaptor.capture());
    assertThat(errorCaptor.getValue())
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            sre -> assertThat(sre.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND));
  }
}
