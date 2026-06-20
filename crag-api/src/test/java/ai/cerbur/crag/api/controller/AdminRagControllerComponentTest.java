package ai.cerbur.crag.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.cerbur.crag.api.controller.advice.GlobalExceptionHandler;
import ai.cerbur.crag.ingestion.api.AdminRagResult;
import ai.cerbur.crag.ingestion.api.AdminRagService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * 轻量组件测试 —— 验证 AdminRagController 成功映射与 JSON 字段.
 *
 * <p>使用 @WebMvcTest 只加载 MVC 切片。通过 @Import 提供 mock AdminRagService 和 GlobalExceptionHandler。
 */
@WebMvcTest
@Import({AdminRagController.class, AdminRagControllerComponentTest.StubConfig.class})
class AdminRagControllerComponentTest {

  @Autowired private AdminRagService adminRagService;

  @Autowired private MockMvc mockMvc;

  @Configuration
  static class StubConfig {

    @Bean
    AdminRagService adminRagService() {
      return mock(AdminRagService.class);
    }

    @Bean
    GlobalExceptionHandler globalExceptionHandler() {
      return new GlobalExceptionHandler();
    }
  }

  @Test
  @DisplayName("successful upload returns AdminRagResponse with correct fields")
  void successfulUpload() throws Exception {
    when(adminRagService.ingest(any(), any(), any()))
        .thenReturn(new AdminRagResult("abc-123", 5, "PENDING", List.of("p-1")));

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
        .andExpect(jsonPath("$.result.status").value("PENDING"))
        .andExpect(jsonPath("$.result.parentChunkIds[0]").value("p-1"));
  }

  @Test
  @DisplayName("response JSON has exactly three top-level fields: success, code, result")
  void responseHasExactlyThreeFields() throws Exception {
    when(adminRagService.ingest(any(), any(), any()))
        .thenReturn(new AdminRagResult("id", 1, "PENDING", List.of("p-abc")));

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
  @DisplayName(
      "response result has exactly four business fields: docId, chunks, status, parentChunkIds")
  void resultHasFourBusinessFields() throws Exception {
    when(adminRagService.ingest(any(), any(), any()))
        .thenReturn(new AdminRagResult("id", 3, "PENDING", List.of("px", "py")));

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
    Set<String> expected = Set.of("docId", "chunks", "status", "parentChunkIds");
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
