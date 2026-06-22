package ai.cerbur.crag.knowledge.app;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
class KnowledgeServiceComponentTest {

  @Autowired private Environment env;
  @Autowired private WebApplicationContext wac;

  @Test
  @DisplayName("Context 加载成功")
  void contextLoads() {}

  @Test
  @DisplayName("spring.application.name 为 knowledge-service")
  void applicationName() {
    assertEquals("knowledge-service", env.getProperty("spring.application.name"));
  }

  @Test
  @DisplayName("Web Application 类型为 SERVLET，确保 Actuator HTTP 端口可监听")
  void webApplicationType() {
    assertInstanceOf(WebApplicationContext.class, wac);
  }
}
