package ai.cerbur.crag.knowledge.app;

import ai.cerbur.crag.grpc.runtime.config.GrpcServerConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@ComponentScan("ai.cerbur.crag.knowledge")
@Import(GrpcServerConfiguration.class)
public class KnowledgeServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(KnowledgeServiceApplication.class, args);
  }
}
