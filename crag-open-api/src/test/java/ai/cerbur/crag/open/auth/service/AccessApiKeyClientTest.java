package ai.cerbur.crag.open.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.cerbur.crag.contracts.access.v1.ApiKeyServiceGrpc;
import ai.cerbur.crag.contracts.access.v1.AuthenticateApiKeyRequest;
import ai.cerbur.crag.contracts.access.v1.AuthenticatedApiKey;
import ai.cerbur.crag.open.auth.service.AccessApiKeyClient.DownstreamTimeoutException;
import ai.cerbur.crag.open.auth.service.AccessApiKeyClient.DownstreamUnavailableException;
import ai.cerbur.crag.open.auth.service.AccessApiKeyClient.InvalidApiKeyException;
import ai.cerbur.crag.open.authcache.CachedApiKey;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * AccessApiKeyClient 进程内 gRPC 组件测试（plan_21/21.10）。
 *
 * <p>验证 gRPC Status → 业务异常映射与字段映射。真实跨服务调用由 21.13 Docker 全链路回归证明。
 */
@DisplayName("AccessApiKeyClient in-process gRPC")
class AccessApiKeyClientTest {

  private Server server;
  private ManagedChannel channel;
  private FakeApiKeyService fake;
  private AccessApiKeyClient client;

  @BeforeEach
  void setUp() throws IOException {
    fake = new FakeApiKeyService();
    String name = InProcessServerBuilder.generateName();
    server = InProcessServerBuilder.forName(name).directExecutor().addService(fake).build().start();
    channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    client = new AccessApiKeyClient(channel, 5000L);
  }

  @AfterEach
  void tearDown() {
    if (channel != null) channel.shutdownNow();
    if (server != null) server.shutdownNow();
  }

  @Test
  @DisplayName("鉴权成功返回 CachedApiKey（apiKeyId/tenantId/knowledgeBaseId/版本水位/expiresAt）")
  void authenticateSuccess() {
    fake.response =
        AuthenticatedApiKey.newBuilder()
            .setApiKeyId("1001")
            .setTenantId("5001")
            .setKnowledgeBaseId("9001")
            .setExpiresAtEpochMillis("1800000000000")
            .setKeyVersion(5)
            .setScopeVersion(3)
            .build();

    CachedApiKey result = client.authenticate("crag_prefix_secret");

    assertThat(result.apiKeyId()).isEqualTo(1001L);
    assertThat(result.tenantId()).isEqualTo(5001L);
    assertThat(result.knowledgeBaseId()).isEqualTo(9001L);
    assertThat(result.keyVersion()).isEqualTo(5L);
    assertThat(result.scopeVersion()).isEqualTo(3L);
    assertThat(result.expiresAt()).isEqualTo(Instant.ofEpochMilli(1800000000000L));
  }

  @Test
  @DisplayName("UNAUTHENTICATED → InvalidApiKeyException（40102，不泄漏原因）")
  void unauthenticatedMapsToInvalidApiKey() {
    fake.error = Status.UNAUTHENTICATED.withDescription("key not found").asRuntimeException();
    assertThatThrownBy(() -> client.authenticate("crag_bad"))
        .isInstanceOf(InvalidApiKeyException.class);
  }

  @Test
  @DisplayName("NOT_FOUND → InvalidApiKeyException")
  void notFoundMapsToInvalidApiKey() {
    fake.error = Status.NOT_FOUND.asRuntimeException();
    assertThatThrownBy(() -> client.authenticate("crag_bad"))
        .isInstanceOf(InvalidApiKeyException.class);
  }

  @Test
  @DisplayName("DEADLINE_EXCEEDED → DownstreamTimeoutException")
  void deadlineMapsToTimeout() {
    fake.error = Status.DEADLINE_EXCEEDED.asRuntimeException();
    assertThatThrownBy(() -> client.authenticate("crag_ok"))
        .isInstanceOf(DownstreamTimeoutException.class);
  }

  @Test
  @DisplayName("UNAVAILABLE → DownstreamUnavailableException")
  void unavailableMapsToDownstream() {
    fake.error = Status.UNAVAILABLE.asRuntimeException();
    assertThatThrownBy(() -> client.authenticate("crag_ok"))
        .isInstanceOf(DownstreamUnavailableException.class);
  }

  private static final class FakeApiKeyService extends ApiKeyServiceGrpc.ApiKeyServiceImplBase {
    AuthenticatedApiKey response;
    RuntimeException error;

    @Override
    public void authenticateApiKey(
        AuthenticateApiKeyRequest request, StreamObserver<AuthenticatedApiKey> responseObserver) {
      if (error != null) {
        responseObserver.onError(error);
        return;
      }
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }
}
