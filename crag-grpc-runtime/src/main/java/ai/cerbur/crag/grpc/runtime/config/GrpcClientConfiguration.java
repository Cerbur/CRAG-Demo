package ai.cerbur.crag.grpc.runtime.config;

import ai.cerbur.crag.grpc.runtime.client.DefaultGrpcChannelFactory;
import ai.cerbur.crag.grpc.runtime.client.GrpcChannelFactory;
import ai.cerbur.crag.grpc.runtime.client.GrpcClientProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
public class GrpcClientConfiguration {

  @Autowired private Environment env;

  @Bean
  public GrpcClientProperties grpcClientProperties() {
    GrpcClientProperties props = new GrpcClientProperties();
    props.setCallerService(env.getProperty("crag.grpc.client.caller-service", ""));
    props.setToken(env.getProperty("crag.grpc.client.token", ""));
    String deadlineStr = env.getProperty("crag.grpc.client.max-deadline-millis");
    if (deadlineStr != null) {
      props.setMaxDeadlineMillis(Long.parseLong(deadlineStr));
    }
    String shutdownStr = env.getProperty("crag.grpc.client.channel-shutdown-timeout-millis");
    if (shutdownStr != null) {
      props.setChannelShutdownTimeoutMillis(Long.parseLong(shutdownStr));
    }
    return props;
  }

  @Bean
  public GrpcChannelFactory grpcChannelFactory(GrpcClientProperties properties) {
    return new DefaultGrpcChannelFactory(properties);
  }
}
