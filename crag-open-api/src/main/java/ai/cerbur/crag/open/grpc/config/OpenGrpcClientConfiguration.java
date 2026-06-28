package ai.cerbur.crag.open.grpc.config;

import ai.cerbur.crag.grpc.runtime.client.GrpcChannelFactory;
import io.grpc.ManagedChannel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Open 下游 gRPC client 配置（plan_21/21.10）。
 *
 * <p>建立 Access/RAG 的 channel Bean，供 {@code AccessApiKeyClient} / {@code RagQueryClient} 使用。 调用方身份与
 * service token 由 {@link GrpcChannelFactory} 的拦截器自动附加。channel 在 plaintext 模式下用于 Compose 内部调用。
 */
@Configuration
public class OpenGrpcClientConfiguration {

  @Autowired private GrpcChannelFactory channelFactory;

  @Bean(name = "openAccessChannel")
  public ManagedChannel accessChannel(
      @Value("${crag.open.access.target:access-service:9091}") String target) {
    return channelFactory.create("access-service", target, true);
  }

  @Bean(name = "openRagChannel")
  public ManagedChannel ragChannel(
      @Value("${crag.open.rag.target:rag-service:9093}") String target) {
    return channelFactory.create("rag-service", target, true);
  }
}
