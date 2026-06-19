package ai.cerbur.crag.api.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.cerbur.crag.api.controller.advice.GlobalExceptionHandler;
import ai.cerbur.crag.ingestion.api.AdminRagResult;
import ai.cerbur.crag.ingestion.api.AdminRagService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 轻量组件测试 —— 验证 AdminRagController 成功映射与 JSON 字段.
 *
 * <p>使用 @WebMvcTest 只加载 MVC 切片。通过 @Import 引入手动构造的 Controller（注入 stub service）， AdminRagService 不作为
 * Spring bean，避免 @Autowired 依赖链传播。
 */
@WebMvcTest
@Import(AdminRagControllerComponentTest.StubConfig.class)
class AdminRagControllerComponentTest {

  /** 共享状态，每个测试通过此引用控制 stub 返回值. */
  static final AtomicReference<AdminRagResult> STUB_RESULT =
      new AtomicReference<>(new AdminRagResult("default", 0, "PENDING"));

  @Autowired private MockMvc mockMvc;

  @Configuration
  static class StubConfig {

    @Bean
    GlobalExceptionHandler globalExceptionHandler() {
      return new GlobalExceptionHandler();
    }

    @Bean
    AdminRagController adminRagController() {
      AdminRagService stubService =
          new AdminRagService() {
            @Override
            public AdminRagResult ingest(
                String title, String content, Map<String, Object> metadata) {
              return STUB_RESULT.get();
            }
          };
      return new AdminRagController(stubService);
    }
  }

  @BeforeEach
  void resetStub() {
    STUB_RESULT.set(new AdminRagResult("default", 0, "PENDING"));
  }

  @Test
  @DisplayName("successful upload returns AdminRagResponse with correct fields")
  void successfulUpload() throws Exception {
    STUB_RESULT.set(new AdminRagResult("abc-123", 5, "PENDING"));

    String body =
        """
        {"title": "Test Title", "content": "Test content"}""";

    mockMvc
        .perform(post("/api/v1/admin/rag").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.result.docId").value("abc-123"))
        .andExpect(jsonPath("$.result.chunks").value(5))
        .andExpect(jsonPath("$.result.status").value("PENDING"));
  }

  @Test
  @DisplayName("response JSON has exactly three top-level fields: success, code, result")
  void responseHasExactlyThreeFields() throws Exception {
    STUB_RESULT.set(new AdminRagResult("id", 1, "PENDING"));

    String body =
        """
        {"title": "T", "content": "C"}""";

    String content =
        mockMvc
            .perform(
                post("/api/v1/admin/rag").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    ObjectMapper mapper = new ObjectMapper();
    Map<?, ?> parsed = mapper.readValue(content, Map.class);

    Set<?> keys = parsed.keySet();
    Set<String> expected = Set.of("success", "code", "result");
    if (!keys.equals(expected)) {
      throw new AssertionError("Expected top-level keys " + expected + " but got " + keys);
    }
  }

  @Test
  @DisplayName("response result has only three business fields: docId, chunks, status")
  void resultHasThreeBusinessFields() throws Exception {
    STUB_RESULT.set(new AdminRagResult("id", 3, "PENDING"));

    String body =
        """
        {"title": "T", "content": "C"}""";

    String content =
        mockMvc
            .perform(
                post("/api/v1/admin/rag").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    ObjectMapper mapper = new ObjectMapper();
    Map<?, ?> parsed = mapper.readValue(content, Map.class);
    Map<?, ?> result = (Map<?, ?>) parsed.get("result");

    Set<?> keys = result.keySet();
    Set<String> expected = Set.of("docId", "chunks", "status");
    if (!keys.equals(expected)) {
      throw new AssertionError("Expected result keys " + expected + " but got " + keys);
    }
  }

  @Test
  @DisplayName("validation failure on AdminRag request returns VALIDATION_ERROR")
  void validationFailure() throws Exception {
    mockMvc
        .perform(post("/api/v1/admin/rag").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.code").value(40001));
  }
}
