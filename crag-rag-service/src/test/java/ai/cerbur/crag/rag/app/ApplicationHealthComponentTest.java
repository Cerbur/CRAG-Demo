package ai.cerbur.crag.rag.app;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApplicationHealthComponentTest {

  @LocalServerPort private int port;
  private final RestTemplate restTemplate = new RestTemplate();

  @Test
  @DisplayName("/actuator/health 返回 200 且 status=UP")
  void healthEndpoint_returns200AndStatusUp() {
    ResponseEntity<String> response =
        restTemplate.getForEntity("http://localhost:" + port + "/actuator/health", String.class);
    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertTrue(response.getBody().contains("\"status\":\"UP\""));
  }

  @Test
  @DisplayName("/actuator/health/liveness 返回 200 且 status=UP")
  void livenessEndpoint_returns200AndStatusUp() {
    ResponseEntity<String> response =
        restTemplate.getForEntity(
            "http://localhost:" + port + "/actuator/health/liveness", String.class);
    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertTrue(response.getBody().contains("\"status\":\"UP\""));
  }

  @Test
  @DisplayName("/actuator/health/readiness 返回 200 且 status=UP")
  void readinessEndpoint_returns200AndStatusUp() {
    ResponseEntity<String> response =
        restTemplate.getForEntity(
            "http://localhost:" + port + "/actuator/health/readiness", String.class);
    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertTrue(response.getBody().contains("\"status\":\"UP\""));
  }

  @Test
  @DisplayName("/actuator/env 不暴露")
  void envEndpoint_isNotExposed() {
    assertThrows(
        HttpClientErrorException.NotFound.class,
        () ->
            restTemplate.getForEntity("http://localhost:" + port + "/actuator/env", String.class));
  }
}
