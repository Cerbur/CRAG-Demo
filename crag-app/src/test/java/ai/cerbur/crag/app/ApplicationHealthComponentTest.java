package ai.cerbur.crag.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

/**
 * 轻量组件测试：验证 Spring Boot Actuator 健康端点的暴露面与状态.
 *
 * <p>使用 Spring Context + H2 替身环境，验证 /actuator/health、/actuator/health/liveness、
 * /actuator/health/readiness 返回 HTTP 200 且 status=UP，响应不含 components 详情； /actuator/env 等管理端点不暴露。
 *
 * <p>H2 仅为轻量组件测试替身。本测试可证明 Bean 装配、配置绑定和受控替身下的基础组件协作， 不能证明 PostgreSQL 方言、native
 * SQL、JSONB、pgvector、锁、CAS、真实事务隔离、 容器网络或 Sidecar 协议正确。真实持久化与端到端行为必须通过 Docker HTTP 回归验证。
 *
 * @see constraints/test-workflow.md 1.2 轻量组件测试
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApplicationHealthComponentTest {

  @LocalServerPort private int port;

  private final RestTemplate restTemplate = new RestTemplate();

  private String url(String path) {
    return "http://localhost:" + port + path;
  }

  @Test
  void healthEndpoint_returns200AndStatusUp() {
    ResponseEntity<String> response =
        restTemplate.getForEntity(url("/actuator/health"), String.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).contains("\"status\":\"UP\"");
    assertThat(response.getBody()).doesNotContain("\"components\"");
  }

  @Test
  void livenessEndpoint_returns200AndStatusUp() {
    ResponseEntity<String> response =
        restTemplate.getForEntity(url("/actuator/health/liveness"), String.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).contains("\"status\":\"UP\"");
    assertThat(response.getBody()).doesNotContain("\"components\"");
  }

  @Test
  void readinessEndpoint_returns200AndStatusUp() {
    ResponseEntity<String> response =
        restTemplate.getForEntity(url("/actuator/health/readiness"), String.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).contains("\"status\":\"UP\"");
    assertThat(response.getBody()).doesNotContain("\"components\"");
  }

  @Test
  void envEndpoint_isNotExposed() {
    try {
      restTemplate.getForEntity(url("/actuator/env"), String.class);
      // 如果没有抛出异常，说明返回了非 404 状态码
      assertThat(false).as("/actuator/env 应返回 404").isTrue();
    } catch (HttpClientErrorException.NotFound e) {
      assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
  }
}
