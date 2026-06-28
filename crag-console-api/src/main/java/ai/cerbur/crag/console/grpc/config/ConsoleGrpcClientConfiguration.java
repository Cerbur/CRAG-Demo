package ai.cerbur.crag.console.grpc.config;

import ai.cerbur.crag.grpc.runtime.client.GrpcChannelFactory;
import io.grpc.ManagedChannel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Console 下游 gRPC client 配置（plan_21/21.6）。
 *
 * <p>建立 Access/Knowledge/RAG 的 channel 与 stub Bean，配置 per-use-case deadline。 调用方身份与 service token 由
 * {@link GrpcChannelFactory} 的拦截器自动附加。channel 在 plaintext 模式下用于 Compose 内部调用。
 */
@Configuration
public class ConsoleGrpcClientConfiguration {

  @Autowired private GrpcChannelFactory channelFactory;

  @Bean(name = "consoleAccessChannel")
  public ManagedChannel accessChannel(
      @Value("${crag.console.access.target:access-service:9091}") String target) {
    return channelFactory.create("access-service", target, true);
  }

  @Bean(name = "consoleKnowledgeChannel")
  public ManagedChannel knowledgeChannel(
      @Value("${crag.console.knowledge.target:knowledge-service:9092}") String target) {
    return channelFactory.create("knowledge-service", target, true);
  }

  @Bean(name = "consoleRagChannel")
  public ManagedChannel ragChannel(
      @Value("${crag.console.rag.target:rag-service:9093}") String target) {
    return channelFactory.create("rag-service", target, true);
  }
}
