package ai.cerbur.crag.access.app;

import ai.cerbur.crag.grpc.runtime.config.GrpcServerConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@ComponentScan("ai.cerbur.crag.access")
@Import(GrpcServerConfiguration.class)
public class AccessServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(AccessServiceApplication.class, args);
  }
}
