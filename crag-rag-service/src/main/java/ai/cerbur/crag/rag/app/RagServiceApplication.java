package ai.cerbur.crag.rag.app;

import ai.cerbur.crag.grpc.runtime.config.GrpcServerConfiguration;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScanPackages;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ComponentScan("ai.cerbur.crag")
@EnableJpaRepositories("ai.cerbur.crag.storage.repository")
@EnableScheduling
@Import(GrpcServerConfiguration.class)
public class RagServiceApplication {

  @Bean
  static BeanDefinitionRegistryPostProcessor entityPackageRegistrar() {
    return registry -> EntityScanPackages.register(registry, "ai.cerbur.crag.storage.entity");
  }

  public static void main(String[] args) {
    SpringApplication.run(RagServiceApplication.class, args);
  }
}
