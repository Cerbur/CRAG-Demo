package ai.cerbur.crag.access.app;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

@SpringBootTest
class AccessServiceComponentTest {

  @Autowired private Environment env;

  @Test
  @DisplayName("Context 加载成功")
  void contextLoads() {}

  @Test
  @DisplayName("spring.application.name 为 access-service")
  void applicationName() {
    assertEquals("access-service", env.getProperty("spring.application.name"));
  }
}
