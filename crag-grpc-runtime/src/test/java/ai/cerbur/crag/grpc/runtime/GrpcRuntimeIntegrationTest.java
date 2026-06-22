package ai.cerbur.crag.grpc.runtime;

import static org.junit.jupiter.api.Assertions.*;

import ai.cerbur.crag.grpc.runtime.client.DefaultGrpcChannelFactory;
import ai.cerbur.crag.grpc.runtime.client.GrpcClientProperties;
import ai.cerbur.crag.grpc.runtime.server.GrpcServerLifecycle;
import ai.cerbur.crag.grpc.runtime.server.GrpcServiceAuthenticationInterceptor;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerBuilder;
import io.grpc.Status;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthGrpc;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.services.HealthStatusManager;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GrpcRuntimeIntegrationTest {

  private static final Metadata.Key<String> CALLER_SERVICE_KEY =
      Metadata.Key.of("x-crag-caller-service", Metadata.ASCII_STRING_MARSHALLER);
  private static final Metadata.Key<String> SERVICE_TOKEN_KEY =
      Metadata.Key.of("x-crag-service-token", Metadata.ASCII_STRING_MARSHALLER);

  private static final String VALID_CALLER = "test-caller";
  private static final String VALID_TOKEN = "test-token-123";

  private io.grpc.Server server;
  private ManagedChannel channel;
  private String serverName;

  @AfterEach
  void tearDown() throws Exception {
    if (channel != null) {
      channel.shutdownNow();
      channel.awaitTermination(2, TimeUnit.SECONDS);
    }
    if (server != null) {
      server.shutdownNow();
      server.awaitTermination(2, TimeUnit.SECONDS);
    }
  }

  private static Map<String, String> allowedCallers() {
    Map<String, String> callers = new LinkedHashMap<>();
    callers.put(VALID_CALLER, VALID_TOKEN);
    return callers;
  }

  private void startServerWithAuth(io.grpc.BindableService... services) throws Exception {
    serverName = "test-" + System.nanoTime();
    Map<String, String> callers = allowedCallers();
    GrpcServiceAuthenticationInterceptor interceptor =
        new GrpcServiceAuthenticationInterceptor(callers);

    HealthStatusManager healthManager = new HealthStatusManager();
    ServerBuilder<?> builder = InProcessServerBuilder.forName(serverName);
    builder.addService(healthManager.getHealthService());
    for (io.grpc.BindableService service : services) {
      builder.addService(io.grpc.ServerInterceptors.intercept(service, interceptor));
    }
    server = builder.build().start();
    healthManager.setStatus("", HealthCheckResponse.ServingStatus.SERVING);
    channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
  }

  private Metadata validHeaders() {
    Metadata headers = new Metadata();
    headers.put(CALLER_SERVICE_KEY, VALID_CALLER);
    headers.put(SERVICE_TOKEN_KEY, VALID_TOKEN);
    return headers;
  }

  @Test
  @DisplayName("匿名 Health 检查返回 SERVING")
  void anonymousHealth_returnsServing() throws Exception {
    startServerWithAuth();

    HealthGrpc.HealthBlockingStub stub = HealthGrpc.newBlockingStub(channel);
    HealthCheckResponse response = stub.check(HealthCheckRequest.getDefaultInstance());
    assertEquals(HealthCheckResponse.ServingStatus.SERVING, response.getStatus());
  }

  @Test
  @DisplayName("合法 caller 调用受保护服务成功")
  void validCaller_callsProtectedService() throws Exception {
    startServerWithAuth(new EchoService());

    Metadata headers = validHeaders();
    EchoService.EchoBlockingStub stub =
        EchoService.newBlockingStub(channel)
            .withDeadlineAfter(2, TimeUnit.SECONDS)
            .withInterceptors(new MetadataInterceptor(headers));

    EchoService.EchoResponse response = stub.echo(EchoService.EchoRequest.INSTANCE);
    assertEquals(VALID_CALLER, response.callerService);
  }

  @Test
  @DisplayName("缺少身份返回 UNAUTHENTICATED")
  void missingIdentity_returnsUnauthenticated() throws Exception {
    startServerWithAuth(new EchoService());

    EchoService.EchoBlockingStub stub =
        EchoService.newBlockingStub(channel).withDeadlineAfter(2, TimeUnit.SECONDS);

    var ex =
        assertThrows(
            io.grpc.StatusRuntimeException.class,
            () -> stub.echo(EchoService.EchoRequest.INSTANCE));
    assertEquals(Status.Code.UNAUTHENTICATED, ex.getStatus().getCode());
  }

  @Test
  @DisplayName("未知 caller 返回 UNAUTHENTICATED")
  void unknownCaller_returnsUnauthenticated() throws Exception {
    startServerWithAuth(new EchoService());

    Metadata headers = new Metadata();
    headers.put(CALLER_SERVICE_KEY, "unknown-svc");
    headers.put(SERVICE_TOKEN_KEY, "any-token");

    EchoService.EchoBlockingStub stub =
        EchoService.newBlockingStub(channel)
            .withDeadlineAfter(2, TimeUnit.SECONDS)
            .withInterceptors(new MetadataInterceptor(headers));

    var ex =
        assertThrows(
            io.grpc.StatusRuntimeException.class,
            () -> stub.echo(EchoService.EchoRequest.INSTANCE));
    assertEquals(Status.Code.UNAUTHENTICATED, ex.getStatus().getCode());
  }

  @Test
  @DisplayName("错误 token 返回 UNAUTHENTICATED")
  void wrongToken_returnsUnauthenticated() throws Exception {
    startServerWithAuth(new EchoService());

    Metadata headers = new Metadata();
    headers.put(CALLER_SERVICE_KEY, VALID_CALLER);
    headers.put(SERVICE_TOKEN_KEY, "wrong-token");

    EchoService.EchoBlockingStub stub =
        EchoService.newBlockingStub(channel)
            .withDeadlineAfter(2, TimeUnit.SECONDS)
            .withInterceptors(new MetadataInterceptor(headers));

    var ex =
        assertThrows(
            io.grpc.StatusRuntimeException.class,
            () -> stub.echo(EchoService.EchoRequest.INSTANCE));
    assertEquals(Status.Code.UNAUTHENTICATED, ex.getStatus().getCode());
  }

  @Test
  @DisplayName("Server 生命周期：启动后 SERVING，关闭后 NOT_SERVING")
  void serverLifecycle_servingToNotServing() throws Exception {
    HealthStatusManager healthManager = new HealthStatusManager();
    String name = "lifecycle-test-" + System.nanoTime();
    io.grpc.Server grpcServer =
        InProcessServerBuilder.forName(name)
            .addService(healthManager.getHealthService())
            .build()
            .start();
    healthManager.setStatus("", HealthCheckResponse.ServingStatus.SERVING);

    ManagedChannel ch = InProcessChannelBuilder.forName(name).directExecutor().build();
    HealthGrpc.HealthBlockingStub stub = HealthGrpc.newBlockingStub(ch);

    HealthCheckResponse resp = stub.check(HealthCheckRequest.getDefaultInstance());
    assertEquals(HealthCheckResponse.ServingStatus.SERVING, resp.getStatus());

    healthManager.setStatus("", HealthCheckResponse.ServingStatus.NOT_SERVING);
    grpcServer.shutdown();
    assertTrue(grpcServer.awaitTermination(5, TimeUnit.SECONDS));
    ch.shutdownNow();
  }

  @Test
  @DisplayName("Spring Context 关闭后 Server 在超时内终止")
  void contextClose_serverTerminates() throws Exception {
    HealthStatusManager healthManager = new HealthStatusManager();
    String name = "close-test-" + System.nanoTime();
    io.grpc.Server grpcServer =
        InProcessServerBuilder.forName(name).addService(healthManager.getHealthService()).build();

    GrpcServerLifecycle lifecycle = new GrpcServerLifecycle(grpcServer, healthManager, 5000);
    lifecycle.start();

    assertTrue(lifecycle.isRunning());

    lifecycle.stop();
    assertFalse(lifecycle.isRunning());
  }

  @Test
  @DisplayName("channel factory 追踪并关闭所有 channel")
  void channelFactory_closesAllChannels() throws Exception {
    GrpcClientProperties clientProps = new GrpcClientProperties();
    clientProps.setCallerService(VALID_CALLER);
    clientProps.setToken(VALID_TOKEN);
    clientProps.setMaxDeadlineMillis(10000);
    clientProps.setChannelShutdownTimeoutMillis(2000);

    DefaultGrpcChannelFactory factory = new DefaultGrpcChannelFactory(clientProps);
    ManagedChannel ch1 = factory.create("svc1", "localhost:9991", true);
    ManagedChannel ch2 = factory.create("svc2", "localhost:9992", true);

    assertNotNull(ch1);
    assertNotNull(ch2);

    factory.close();

    assertTrue(ch1.isShutdown());
    assertTrue(ch2.isShutdown());
  }

  private static class MetadataInterceptor implements io.grpc.ClientInterceptor {
    private final Metadata metadata;

    MetadataInterceptor(Metadata metadata) {
      this.metadata = metadata;
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
        MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
      return new io.grpc.ForwardingClientCall.SimpleForwardingClientCall<>(
          next.newCall(method, callOptions)) {
        @Override
        public void start(ClientCall.Listener<RespT> responseListener, Metadata headers) {
          headers.merge(metadata);
          super.start(responseListener, headers);
        }
      };
    }
  }
}
