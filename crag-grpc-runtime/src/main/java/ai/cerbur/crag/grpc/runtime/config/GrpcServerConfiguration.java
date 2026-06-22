package ai.cerbur.crag.grpc.runtime.config;

import ai.cerbur.crag.grpc.runtime.identity.GrpcCallerContext;
import ai.cerbur.crag.grpc.runtime.server.DefaultGrpcCallerContext;
import ai.cerbur.crag.grpc.runtime.server.GrpcServerLifecycle;
import ai.cerbur.crag.grpc.runtime.server.GrpcServerProperties;
import ai.cerbur.crag.grpc.runtime.server.GrpcServiceAuthenticationInterceptor;
import io.grpc.BindableService;
import io.grpc.ServerBuilder;
import io.grpc.services.HealthStatusManager;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
public class GrpcServerConfiguration {

  @Autowired private Environment env;

  @Bean
  public GrpcServerProperties grpcServerProperties() {
    GrpcServerProperties props = new GrpcServerProperties();
    String portStr = env.getProperty("crag.grpc.server.bind-port");
    if (portStr != null) {
      props.setBindPort(Integer.parseInt(portStr));
    }
    String timeoutStr = env.getProperty("crag.grpc.server.shutdown-timeout");
    if (timeoutStr != null) {
      props.setShutdownTimeout(java.time.Duration.parse(timeoutStr));
    }
    return props;
  }

  @Bean
  public HealthStatusManager grpcHealthStatusManager() {
    return new HealthStatusManager();
  }

  @Bean
  public GrpcCallerContext grpcCallerContext() {
    return new DefaultGrpcCallerContext();
  }

  @Bean
  public GrpcServerLifecycle grpcServerLifecycle(
      GrpcServerProperties properties,
      HealthStatusManager healthStatusManager,
      List<BindableService> services) {
    if (properties.getAllowedCallers() == null || properties.getAllowedCallers().isEmpty()) {
      throw new IllegalStateException("crag.grpc.server.allowed-callers must not be empty");
    }

    GrpcServiceAuthenticationInterceptor authInterceptor =
        new GrpcServiceAuthenticationInterceptor(properties.getAllowedCallers());

    ServerBuilder<?> builder = ServerBuilder.forPort(properties.getBindPort());
    builder.addService(healthStatusManager.getHealthService());
    for (BindableService service : services) {
      builder.addService(io.grpc.ServerInterceptors.intercept(service, authInterceptor));
    }

    return new GrpcServerLifecycle(
        builder.build(), healthStatusManager, properties.getShutdownTimeout().toMillis());
  }
}
