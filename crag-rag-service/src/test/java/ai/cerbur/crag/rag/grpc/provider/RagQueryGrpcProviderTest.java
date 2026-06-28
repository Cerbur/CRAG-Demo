package ai.cerbur.crag.rag.grpc.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.cerbur.crag.contracts.rag.v1.QueryRequest;
import ai.cerbur.crag.contracts.rag.v1.QueryResponse;
import ai.cerbur.crag.query.api.InvalidQueryException;
import ai.cerbur.crag.query.api.QuerySource;
import ai.cerbur.crag.query.api.UserQueryOutcome;
import ai.cerbur.crag.query.api.UserQueryResult;
import ai.cerbur.crag.query.api.UserQueryService;
import ai.cerbur.crag.rag.grpc.security.RagRpcAuthorizer;
import ai.cerbur.crag.retrieval.api.result.ParentEvidenceResult;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** RagQueryGrpcProvider 单元测试（Plan 21.4）：验证调用方授权、十进制 ID 解析、Citation 映射与错误转换. */
@DisplayName("RagQueryGrpcProvider 调用方授权与 Citation 映射")
@ExtendWith(MockitoExtension.class)
class RagQueryGrpcProviderTest {

  @Mock private UserQueryService userQueryService;
  @Mock private RagRpcAuthorizer authorizer;
  @Mock private StreamObserver<QueryResponse> responseObserver;

  @InjectMocks private RagQueryGrpcProvider provider;

  @Test
  @DisplayName("Open API 调用 → 解析 knowledgeBaseId、调用 answerWithEvidence、返回 Citation")
  void openApiCallReturnsCitations() {
    long kb = 4096L;
    long parentChunkId = 100L;
    long docId = 5001L;
    QueryRequest request =
        QueryRequest.newBuilder()
            .setKnowledgeBaseId(Long.toString(kb))
            .setQuestion("question")
            .setTraceId("trace-1")
            .build();
    UserQueryOutcome outcome =
        new UserQueryOutcome(
            new UserQueryResult(
                "answer", List.of(new QuerySource("S1", parentChunkId, List.of(1L)))),
            List.of(new ParentEvidenceResult(parentChunkId, docId, "parent content", List.of(1L))));
    when(userQueryService.answerWithEvidence(eq(kb), anyString())).thenReturn(outcome);

    provider.query(request, responseObserver);

    verify(authorizer).requireOpenApi();
    ArgumentCaptor<QueryResponse> captor = ArgumentCaptor.forClass(QueryResponse.class);
    verify(responseObserver).onNext(captor.capture());
    verify(responseObserver).onCompleted();
    QueryResponse response = captor.getValue();
    assertThat(response.getAnswer()).isEqualTo("answer");
    assertThat(response.getSourcesCount()).isEqualTo(1);
    assertThat(response.getSources(0).getReference()).isEqualTo("S1");
    assertThat(response.getSources(0).getDocumentId()).isEqualTo(Long.toString(docId));
    assertThat(response.getSources(0).getExcerpt()).isEqualTo("parent content");
  }

  @Test
  @DisplayName("非 Open API 调用 → PERMISSION_DENIED 错误")
  void nonOpenApiRejected() {
    doThrow(Status.PERMISSION_DENIED.withDescription("caller not allowed").asRuntimeException())
        .when(authorizer)
        .requireOpenApi();
    QueryRequest request =
        QueryRequest.newBuilder().setKnowledgeBaseId("1").setQuestion("q").build();

    provider.query(request, responseObserver);

    ArgumentCaptor<Throwable> errorCaptor = ArgumentCaptor.forClass(Throwable.class);
    verify(responseObserver).onError(errorCaptor.capture());
    assertThat(errorCaptor.getValue())
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            sre -> assertThat(sre.getStatus().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED));
  }

  @Test
  @DisplayName("非法 knowledgeBaseId → INVALID_ARGUMENT 错误")
  void invalidKnowledgeBaseIdRejected() {
    QueryRequest request =
        QueryRequest.newBuilder().setKnowledgeBaseId("not-a-number").setQuestion("q").build();

    provider.query(request, responseObserver);

    ArgumentCaptor<Throwable> errorCaptor = ArgumentCaptor.forClass(Throwable.class);
    verify(responseObserver).onError(errorCaptor.capture());
    assertThat(errorCaptor.getValue())
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            sre -> assertThat(sre.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT));
  }

  @Test
  @DisplayName("InvalidQueryException → INVALID_ARGUMENT")
  void invalidQueryMapsToInvalidArgument() {
    when(userQueryService.answerWithEvidence(anyLong(), anyString()))
        .thenThrow(
            new InvalidQueryException(InvalidQueryException.Reason.QUESTION_REQUIRED, "blank"));
    QueryRequest request =
        QueryRequest.newBuilder().setKnowledgeBaseId("1").setQuestion("").build();

    provider.query(request, responseObserver);

    ArgumentCaptor<Throwable> errorCaptor = ArgumentCaptor.forClass(Throwable.class);
    verify(responseObserver).onError(errorCaptor.capture());
    assertThat(errorCaptor.getValue())
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            sre -> assertThat(sre.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT));
  }

  @Test
  @DisplayName("长 excerpt 截断到 500 字符")
  void longExcerptTruncated() {
    long kb = 4097L;
    String longContent = "x".repeat(800);
    QueryRequest request =
        QueryRequest.newBuilder().setKnowledgeBaseId(Long.toString(kb)).setQuestion("q").build();
    when(userQueryService.answerWithEvidence(eq(kb), anyString()))
        .thenReturn(
            new UserQueryOutcome(
                new UserQueryResult("a", List.of(new QuerySource("S1", 100L, List.of(1L)))),
                List.of(new ParentEvidenceResult(100L, 5001L, longContent, List.of(1L)))));

    provider.query(request, responseObserver);

    ArgumentCaptor<QueryResponse> captor = ArgumentCaptor.forClass(QueryResponse.class);
    verify(responseObserver).onNext(captor.capture());
    assertThat(captor.getValue().getSources(0).getExcerpt()).hasSize(500);
  }
}
