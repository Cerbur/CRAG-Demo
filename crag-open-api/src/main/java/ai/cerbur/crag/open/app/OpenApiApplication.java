package ai.cerbur.crag.open.app;

import ai.cerbur.crag.grpc.runtime.config.GrpcClientConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@ComponentScan("ai.cerbur.crag.open")
@Import(GrpcClientConfiguration.class)
public class OpenApiApplication {

  public static void main(String[] args) {
    SpringApplication.run(OpenApiApplication.class, args);
  }
}
