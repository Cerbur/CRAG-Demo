package ai.cerbur.crag.console.app;

import ai.cerbur.crag.grpc.runtime.config.GrpcClientConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@ComponentScan("ai.cerbur.crag.console")
@Import(GrpcClientConfiguration.class)
public class ConsoleApiApplication {

  public static void main(String[] args) {
    SpringApplication.run(ConsoleApiApplication.class, args);
  }
}
