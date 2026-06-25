package ai.cerbur.crag.knowledge.controller.smoke;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.MULTIPART_FORM_DATA;

import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;

/**
 * Knowledge smoke HTTP 入口组件测试：H2 + 真实 filestore + smoke profile，覆盖创建知识库、multipart
 * 上传、查询、读回文件、事件诊断与非法扩展名 400。
 *
 * <p>publisher/consumer 在测试中禁用，DOC_UPLOADED 行保持 PENDING；真实 Redis Streams 发布由 Docker HTTP 回归证明。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("smoke")
@TestPropertySource(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:knowledge-smoke-web;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.datasource.username=sa",
      "spring.sql.init.mode=always",
      "spring.sql.init.schema-locations=classpath:schema-knowledge.sql",
      "crag.event.publisher.enabled=false",
      "crag.event.consumer.enabled=false",
      "crag.event.consumer.consumer-name=knowledge-smoke-web"
    })
@DisplayName("Knowledge smoke controller")
class KnowledgeSmokeControllerComponentTest {

  private static final AtomicLong TENANT_SEQ = new AtomicLong(9100L);

  @LocalServerPort private int port;

  private RestClient client;
  private String baseUrl;

  @DynamicPropertySource
  static void filestoreRoot(DynamicPropertyRegistry registry) throws IOException {
    Path dir = Files.createTempDirectory("knowledge-smoke-web");
    registry.add("crag.knowledge.filestore.root", dir::toString);
  }

  @BeforeEach
  void setUp() {
    client = RestClient.create();
    baseUrl = "http://localhost:" + port + "/api/v1/smoke/knowledge";
  }

  @Test
  @DisplayName("创建知识库、上传 .txt、查询文档 PENDING、读回原始内容、事件诊断 PENDING")
  void uploadTxtGetReadAndEvent() {
    long tenant = TENANT_SEQ.incrementAndGet();
    String knowledgeBaseId = createKnowledgeBase(tenant, "kb-web");

    byte[] content = "hello knowledge smoke".getBytes(StandardCharsets.UTF_8);
    String sha256 = sha256(content);
    String docId = upload(knowledgeBaseId, tenant, "doc.txt", content, sha256);

    String document = getJson(baseUrl + "/documents/" + docId + "?tenantId=" + tenant);
    assertThat((String) JsonPath.read(document, "$.result.ingestionStatus")).isEqualTo("PENDING");
    assertThat((String) JsonPath.read(document, "$.result.sha256")).isEqualTo(sha256);

    byte[] body =
        client
            .get()
            .uri(baseUrl + "/documents/" + docId + "/file?tenantId=" + tenant)
            .retrieve()
            .body(byte[].class);
    assertThat(body).isEqualTo(content);

    String event = getJson(baseUrl + "/documents/" + docId + "/event");
    assertThat((String) JsonPath.read(event, "$.result.outboxStatus")).isEqualTo("PENDING");
  }

  @Test
  @DisplayName("非法扩展名上传返回 4xx")
  void uploadRejectsBadExtension() {
    long tenant = TENANT_SEQ.incrementAndGet();
    String knowledgeBaseId = createKnowledgeBase(tenant, "kb-web");
    byte[] content = "x".getBytes(StandardCharsets.UTF_8);

    int status =
        client
            .post()
            .uri(baseUrl + "/documents/upload")
            .contentType(MULTIPART_FORM_DATA)
            .body(multipart(knowledgeBaseId, tenant, "payload.exe", content, sha256(content)))
            .retrieve()
            .onStatus(s -> s.is4xxClientError() || s.is5xxServerError(), (req, res) -> {})
            .toEntity(String.class)
            .getStatusCode()
            .value();
    assertThat(status).isBetween(400, 499);
  }

  private String createKnowledgeBase(long tenant, String name) {
    String body =
        client
            .post()
            .uri(baseUrl + "/knowledge-bases")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                "{\"tenantId\":\""
                    + tenant
                    + "\",\"name\":\""
                    + name
                    + "\",\"createdByUserId\":\"1\"}")
            .retrieve()
            .body(String.class);
    return JsonPath.read(body, "$.result.knowledgeBaseId");
  }

  private String upload(
      String knowledgeBaseId, long tenant, String filename, byte[] content, String sha256) {
    String body =
        client
            .post()
            .uri(baseUrl + "/documents/upload")
            .contentType(MULTIPART_FORM_DATA)
            .body(multipart(knowledgeBaseId, tenant, filename, content, sha256))
            .retrieve()
            .body(String.class);
    return JsonPath.read(body, "$.result.docId");
  }

  private String getJson(String url) {
    return client.get().uri(url).retrieve().body(String.class);
  }

  private static org.springframework.util.MultiValueMap<
          String, org.springframework.http.HttpEntity<?>>
      multipart(
          String knowledgeBaseId, long tenant, String filename, byte[] content, String sha256) {
    MultipartBodyBuilder builder = new MultipartBodyBuilder();
    builder.part("tenantId", Long.toString(tenant));
    builder.part("knowledgeBaseId", knowledgeBaseId);
    builder.part("uploadedByUserId", "1");
    builder.part("sha256", sha256);
    builder.part("sizeBytes", Long.toString(content.length));
    builder.part(
        "file",
        new ByteArrayResource(content) {
          @Override
          public String getFilename() {
            return filename;
          }
        });
    return builder.build();
  }

  private static String sha256(byte[] bytes) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
      StringBuilder sb = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
