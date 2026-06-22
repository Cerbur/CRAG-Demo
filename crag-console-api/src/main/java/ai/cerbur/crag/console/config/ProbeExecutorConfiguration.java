package ai.cerbur.crag.console.config;

import ai.cerbur.crag.contracts.platform.v1.PlatformProbeServiceGrpc;
import ai.cerbur.crag.grpc.runtime.client.GrpcChannelFactory;
import io.grpc.ManagedChannel;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class ProbeExecutorConfiguration {

  @Autowired private GrpcChannelFactory channelFactory;
  @Autowired private Environment env;

  @Bean("probeExecutor")
  public ThreadPoolTaskExecutor probeExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(3);
    executor.setMaxPoolSize(3);
    executor.setQueueCapacity(0);
    executor.setThreadNamePrefix("console-probe-");
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(5);
    return executor;
  }

  @Bean
  public Map<String, PlatformProbeServiceGrpc.PlatformProbeServiceBlockingStub> probeStubs() {
    Map<String, PlatformProbeServiceGrpc.PlatformProbeServiceBlockingStub> stubs =
        new LinkedHashMap<>();
    String[] targets = {"access-service", "knowledge-service", "rag-service"};
    for (String target : targets) {
      String address = env.getProperty("crag.grpc.probe.targets." + target);
      if (address != null) {
        ManagedChannel channel = channelFactory.create(target, address, true);
        stubs.put(target, PlatformProbeServiceGrpc.newBlockingStub(channel));
      }
    }
    return stubs;
  }
}
