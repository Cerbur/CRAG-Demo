package ai.cerbur.crag.grpc.runtime.config;

import ai.cerbur.crag.grpc.runtime.identity.GrpcCallerContext;
import ai.cerbur.crag.grpc.runtime.server.DefaultGrpcCallerContext;
import ai.cerbur.crag.grpc.runtime.server.GrpcServerLifecycle;
import ai.cerbur.crag.grpc.runtime.server.GrpcServerProperties;
import ai.cerbur.crag.grpc.runtime.server.GrpcServiceAuthenticationInterceptor;
import io.grpc.BindableService;
import io.grpc.ServerBuilder;
import io.grpc.services.HealthStatusManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
public class GrpcServerConfiguration {

  private static final String ALLOWED_CALLERS_PREFIX = "crag.grpc.server.allowed-callers.";

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
      props.setShutdownTimeout(
          java.time.Duration.parse(timeoutStr.startsWith("PT") ? timeoutStr : "PT" + timeoutStr));
    }
    Map<String, String> callers = new LinkedHashMap<>();
    // Dynamically read all allowed-callers from environment without hardcoding caller names
    for (String propertyName : getPropertiesWithPrefix(ALLOWED_CALLERS_PREFIX)) {
      String caller = propertyName.substring(ALLOWED_CALLERS_PREFIX.length());
      String token = env.getProperty(propertyName);
      if (token != null && !token.isBlank()) {
        callers.put(caller, token);
      }
    }
    if (!callers.isEmpty()) {
      props.setAllowedCallers(callers);
    }
    return props;
  }

  private List<String> getPropertiesWithPrefix(String prefix) {
    List<String> result = new java.util.ArrayList<>();
    if (env instanceof org.springframework.core.env.ConfigurableEnvironment configurableEnv) {
      for (org.springframework.core.env.PropertySource<?> source :
          configurableEnv.getPropertySources()) {
        if (source instanceof org.springframework.core.env.EnumerablePropertySource<?> enumerable) {
          for (String name : enumerable.getPropertyNames()) {
            if (name.startsWith(prefix)) {
              result.add(name);
            }
          }
        }
      }
    }
    return result.stream().distinct().toList();
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
