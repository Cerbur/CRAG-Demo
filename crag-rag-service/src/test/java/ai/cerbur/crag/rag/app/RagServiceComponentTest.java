package ai.cerbur.crag.rag.app;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

@SpringBootTest
@Import(TestCragIdConfig.class)
class RagServiceComponentTest {

  @Autowired private Environment env;

  @Test
  @DisplayName("Context 加载成功")
  void contextLoads() {}

  @Test
  @DisplayName("spring.application.name 为 rag-service")
  void applicationName() {
    assertEquals("rag-service", env.getProperty("spring.application.name"));
  }
}
