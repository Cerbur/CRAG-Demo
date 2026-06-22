package ai.cerbur.crag.grpc.runtime.client;

import ai.cerbur.crag.grpc.runtime.server.GrpcServiceAuthenticationInterceptor;
import io.grpc.ClientInterceptor;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DefaultGrpcChannelFactory implements GrpcChannelFactory, AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(DefaultGrpcChannelFactory.class);

  private final GrpcClientProperties properties;
  private final long shutdownTimeoutMillis;
  private final List<ManagedChannel> channels = new CopyOnWriteArrayList<>();

  public DefaultGrpcChannelFactory(GrpcClientProperties properties) {
    this.properties = properties;
    this.shutdownTimeoutMillis = properties.getChannelShutdownTimeoutMillis();
  }

  @Override
  public ManagedChannel create(String targetName, String target, boolean plaintext) {
    if (properties.getCallerService() == null || properties.getCallerService().isBlank()) {
      throw new IllegalStateException("crag.grpc.client.caller-service must not be blank");
    }
    if (properties.getToken() == null || properties.getToken().isBlank()) {
      throw new IllegalStateException("crag.grpc.client.token must not be blank");
    }

    ManagedChannelBuilder<?> builder = ManagedChannelBuilder.forTarget(target);

    List<ClientInterceptor> interceptors = new ArrayList<>();
    interceptors.add(
        new MetadataAttachingInterceptor(properties.getCallerService(), properties.getToken()));
    interceptors.add(new DeadlineGuardClientInterceptor(properties.getMaxDeadlineMillis()));
    builder.intercept(interceptors);

    if (plaintext) {
      builder.usePlaintext();
    }

    ManagedChannel channel = builder.build();
    channels.add(channel);
    log.info("Created gRPC channel to {} ({})", targetName, target);
    return channel;
  }

  @Override
  public void close() {
    for (ManagedChannel channel : channels) {
      try {
        channel.shutdown();
        if (!channel.awaitTermination(shutdownTimeoutMillis, TimeUnit.MILLISECONDS)) {
          channel.shutdownNow();
        }
      } catch (InterruptedException e) {
        channel.shutdownNow();
        Thread.currentThread().interrupt();
      }
    }
    channels.clear();
  }

  static class MetadataAttachingInterceptor implements ClientInterceptor {
    private final String callerService;
    private final String token;

    MetadataAttachingInterceptor(String callerService, String token) {
      this.callerService = callerService;
      this.token = token;
    }

    @Override
    public <ReqT, RespT> io.grpc.ClientCall<ReqT, RespT> interceptCall(
        io.grpc.MethodDescriptor<ReqT, RespT> method,
        io.grpc.CallOptions callOptions,
        io.grpc.Channel next) {
      return new io.grpc.ForwardingClientCall.SimpleForwardingClientCall<>(
          next.newCall(method, callOptions)) {
        @Override
        public void start(
            io.grpc.ClientCall.Listener<RespT> responseListener, io.grpc.Metadata headers) {
          headers.put(GrpcServiceAuthenticationInterceptor.CALLER_SERVICE_KEY, callerService);
          headers.put(GrpcServiceAuthenticationInterceptor.SERVICE_TOKEN_KEY, token);
          super.start(responseListener, headers);
        }
      };
    }
  }
}
