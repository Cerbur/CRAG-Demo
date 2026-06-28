package ai.cerbur.crag.open.query.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.cerbur.crag.contracts.rag.v1.Citation;
import ai.cerbur.crag.contracts.rag.v1.QueryRequest;
import ai.cerbur.crag.contracts.rag.v1.QueryResponse;
import ai.cerbur.crag.contracts.rag.v1.QueryServiceGrpc;
import ai.cerbur.crag.open.query.service.RagQueryClient.DownstreamTimeoutException;
import ai.cerbur.crag.open.query.service.RagQueryClient.DownstreamUnavailableException;
import ai.cerbur.crag.open.query.service.RagQueryClient.InvalidQueryException;
import ai.cerbur.crag.open.query.service.RagQueryClient.KnowledgeBaseNotFoundException;
import ai.cerbur.crag.open.query.service.RagQueryClient.LlmUnavailableException;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RagQueryClient 进程内 gRPC 组件测试（plan_21/21.10）。
 *
 * <p>验证 gRPC Status → 业务异常映射、source 映射（reference/documentId/excerpt）与 excerpt 500 字符防御截断。
 */
@DisplayName("RagQueryClient in-process gRPC")
class RagQueryClientTest {

  private Server server;
  private ManagedChannel channel;
  private FakeQueryService fake;
  private RagQueryClient client;

  @BeforeEach
  void setUp() throws IOException {
    fake = new FakeQueryService();
    String name = InProcessServerBuilder.generateName();
    server = InProcessServerBuilder.forName(name).directExecutor().addService(fake).build().start();
    channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    client = new RagQueryClient(channel, 5000L);
  }

  @AfterEach
  void tearDown() {
    if (channel != null) channel.shutdownNow();
    if (server != null) server.shutdownNow();
  }

  @Test
  @DisplayName("Query 成功返回 answer + sources（reference/documentId/excerpt）")
  void querySuccess() {
    fake.response =
        QueryResponse.newBuilder()
            .setAnswer("因为 X 所以 Y")
            .addSources(
                Citation.newBuilder()
                    .setReference("[S1]")
                    .setDocumentId("12345")
                    .setExcerpt("段落内容"))
            .build();

    RagQueryClient.QueryResult result = client.query(9001L, "为什么？", "trace-1");

    assertThat(result.answer()).isEqualTo("因为 X 所以 Y");
    assertThat(result.sources()).hasSize(1);
    assertThat(result.sources().get(0).reference()).isEqualTo("[S1]");
    assertThat(result.sources().get(0).documentId()).isEqualTo("12345");
    assertThat(result.sources().get(0).excerpt()).isEqualTo("段落内容");
  }

  @Test
  @DisplayName("source excerpt ≤500 字符不截断")
  void excerptUnderLimitNotTruncated() {
    String excerpt = "a".repeat(500);
    fake.response =
        QueryResponse.newBuilder()
            .setAnswer("ans")
            .addSources(
                Citation.newBuilder().setReference("[S1]").setDocumentId("1").setExcerpt(excerpt))
            .build();

    RagQueryClient.QueryResult result = client.query(9001L, "q", "t");

    assertThat(result.sources().get(0).excerpt()).hasSize(500);
  }

  @Test
  @DisplayName("source excerpt >500 字符防御截断到 500")
  void excerptOverLimitTruncated() {
    String excerpt = "a".repeat(600);
    fake.response =
        QueryResponse.newBuilder()
            .setAnswer("ans")
            .addSources(
                Citation.newBuilder().setReference("[S1]").setDocumentId("1").setExcerpt(excerpt))
            .build();

    RagQueryClient.QueryResult result = client.query(9001L, "q", "t");

    assertThat(result.sources().get(0).excerpt()).hasSize(500);
  }

  @Test
  @DisplayName("空 sources 返回空列表")
  void emptySources() {
    fake.response = QueryResponse.newBuilder().setAnswer("ans").build();
    RagQueryClient.QueryResult result = client.query(9001L, "q", "t");
    assertThat(result.sources()).isEmpty();
  }

  @Test
  @DisplayName("INVALID_ARGUMENT → InvalidQueryException")
  void invalidArgumentMapsToInvalidQuery() {
    fake.error = Status.INVALID_ARGUMENT.asRuntimeException();
    assertThatThrownBy(() -> client.query(9001L, "q", "t"))
        .isInstanceOf(InvalidQueryException.class);
  }

  @Test
  @DisplayName("NOT_FOUND → KnowledgeBaseNotFoundException")
  void notFoundMapsToKbNotFound() {
    fake.error = Status.NOT_FOUND.asRuntimeException();
    assertThatThrownBy(() -> client.query(9001L, "q", "t"))
        .isInstanceOf(KnowledgeBaseNotFoundException.class);
  }

  @Test
  @DisplayName("UNAVAILABLE → LlmUnavailableException（50201）")
  void unavailableMapsToLlmUnavailable() {
    fake.error = Status.UNAVAILABLE.asRuntimeException();
    assertThatThrownBy(() -> client.query(9001L, "q", "t"))
        .isInstanceOf(LlmUnavailableException.class);
  }

  @Test
  @DisplayName("DEADLINE_EXCEEDED → DownstreamTimeoutException")
  void deadlineMapsToTimeout() {
    fake.error = Status.DEADLINE_EXCEEDED.asRuntimeException();
    assertThatThrownBy(() -> client.query(9001L, "q", "t"))
        .isInstanceOf(DownstreamTimeoutException.class);
  }

  @Test
  @DisplayName("INTERNAL → DownstreamUnavailableException")
  void internalMapsToDownstream() {
    fake.error = Status.INTERNAL.asRuntimeException();
    assertThatThrownBy(() -> client.query(9001L, "q", "t"))
        .isInstanceOf(DownstreamUnavailableException.class);
  }

  private static final class FakeQueryService extends QueryServiceGrpc.QueryServiceImplBase {
    QueryResponse response;
    RuntimeException error;
    QueryRequest lastRequest;

    @Override
    public void query(QueryRequest request, StreamObserver<QueryResponse> responseObserver) {
      this.lastRequest = request;
      if (error != null) {
        responseObserver.onError(error);
        return;
      }
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }
}
