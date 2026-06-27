package ai.cerbur.crag.access.controller.smoke;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * 默认 Profile 不暴露 Access smoke HTTP 入口的轻量组件测试。
 *
 * <p>验证 {@code AccessSmokeController}（{@code @Profile("smoke")}）在默认启动下不装配，端点不可达。
 */
@SpringBootTest
class AccessSmokeDefaultDisabledComponentTest {

  @Autowired private ApplicationContext applicationContext;

  @Test
  @DisplayName("默认 Profile 不装配 smoke Controller")
  void smokeControllerNotLoadedByDefault() {
    assertThrows(
        NoSuchBeanDefinitionException.class,
        () -> applicationContext.getBean(AccessSmokeController.class));
  }
}
