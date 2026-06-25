package ai.cerbur.crag.knowledge.smoke.controller;

import static org.assertj.core.api.Assertions.assertThat;

import ai.cerbur.crag.knowledge.smoke.KnowledgeSmokeTestConfig;
import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;

/**
 * Drives the Knowledge smoke HTTP endpoints over H2: a POST creates an outbox event, and the query
 * endpoints return diagnostic summaries. The publisher/consumer schedulers are not wired here, so
 * the event stays PENDING with no processed row; the publish/consume/DLQ cycle is proven by the
 * Docker HTTP regressions.
 */
@SpringBootTest(
    classes = KnowledgeSmokeTestConfig.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("smoke")
@TestPropertySource(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:smoke-controller;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.datasource.username=sa",
      "spring.sql.init.mode=always",
      "spring.sql.init.schema-locations=classpath:schema-event-smoke.sql",
      "crag.event.consumer.consumer-name=knowledge-smoke-1"
    })
@DisplayName("KnowledgeEventSmokeController")
class KnowledgeEventSmokeControllerComponentTest {

  @LocalServerPort private int port;

  private RestClient client;

  @BeforeEach
  void setUp() {
    client = RestClient.create();
  }

  private String baseUrl() {
    return "http://localhost:" + port + "/api/v1/smoke/events";
  }

  private ResponseEntity<String> post(String body) {
    return client
        .post()
        .uri(baseUrl())
        .contentType(MediaType.APPLICATION_JSON)
        .body(body)
        .retrieve()
        .toEntity(String.class);
  }

  private ResponseEntity<String> get(String url) {
    return client.get().uri(url).retrieve().toEntity(String.class);
  }

  @Test
  @DisplayName("POST creates a smoke event and GET returns its diagnostics")
  void createsEventAndQueriesIt() {
    String runId = "run-" + UUID.randomUUID();
    ResponseEntity<String> created =
        post("{\"runId\":\"" + runId + "\",\"message\":\"smoke\",\"failMode\":\"NONE\"}");

    assertThat(created.getStatusCode().is2xxSuccessful()).isTrue();
    String eventId = JsonPath.read(created.getBody(), "$.result.eventId");
    assertThat(eventId).isNotBlank();
    assertThat((String) JsonPath.read(created.getBody(), "$.result.outboxStatus"))
        .isEqualTo("PENDING");
    assertThat((String) JsonPath.read(created.getBody(), "$.result.runId")).isEqualTo(runId);

    ResponseEntity<String> found = get(baseUrl() + "/" + eventId);
    assertThat(found.getStatusCode().is2xxSuccessful()).isTrue();
    assertThat((String) JsonPath.read(found.getBody(), "$.result.outboxStatus"))
        .isEqualTo("PENDING");
    assertThat(JsonPath.<Object>read(found.getBody(), "$.result.processedStatus")).isNull();
    assertThat((Boolean) JsonPath.read(found.getBody(), "$.result.deadLettered")).isFalse();
  }

  @Test
  @DisplayName("GET by runId returns the events created for that run")
  void findByRunId() {
    String runId = "run-" + UUID.randomUUID();
    post("{\"runId\":\"" + runId + "\",\"message\":\"a\",\"failMode\":\"NONE\"}");

    ResponseEntity<String> found = get(baseUrl() + "?runId=" + runId);

    assertThat(found.getStatusCode().is2xxSuccessful()).isTrue();
    assertThat((Integer) JsonPath.read(found.getBody(), "$.result.length()")).isEqualTo(1);
    assertThat((String) JsonPath.read(found.getBody(), "$.result[0].runId")).isEqualTo(runId);
  }

  @Test
  @DisplayName("POST rejects a request missing runId")
  void rejectsMissingRunId() {
    ResponseEntity<String> rejected =
        client
            .post()
            .uri(baseUrl())
            .contentType(MediaType.APPLICATION_JSON)
            .body("{\"message\":\"smoke\",\"failMode\":\"NONE\"}")
            .retrieve()
            .onStatus(status -> true, (request, response) -> {})
            .toEntity(String.class);

    assertThat(rejected.getStatusCode().is4xxClientError()).isTrue();
  }
}
