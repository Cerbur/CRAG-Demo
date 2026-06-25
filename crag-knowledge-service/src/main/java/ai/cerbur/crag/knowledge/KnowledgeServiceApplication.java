package ai.cerbur.crag.knowledge;

import ai.cerbur.crag.grpc.runtime.config.GrpcServerConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;

/**
 * Knowledge 服务组合根，直接位于 {@code ai.cerbur.crag.knowledge} 根包。
 *
 * <p>组件扫描限定在 Knowledge 包，避免误装配其他服务的 Bean；gRPC Server 生命周期由 {@link GrpcServerConfiguration} 提供，所有
 * Spring 管理的 {@code BindableService} 自动注册。
 */
@SpringBootApplication
@ComponentScan("ai.cerbur.crag.knowledge")
@Import(GrpcServerConfiguration.class)
public class KnowledgeServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(KnowledgeServiceApplication.class, args);
  }
}
