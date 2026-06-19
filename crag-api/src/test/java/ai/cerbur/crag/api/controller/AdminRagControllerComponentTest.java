package ai.cerbur.crag.api.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.cerbur.crag.api.controller.advice.GlobalExceptionHandler;
import ai.cerbur.crag.ingestion.api.AdminRagResult;
import ai.cerbur.crag.ingestion.api.AdminRagService;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/**
 * 轻量组件测试 —— 验证 AdminRagController 成功映射与 JSON 字段.
 *
 * <p>使用 MockMvcBuilders.standaloneSetup，用匿名 stub 替代 AdminRagService。
 */
class AdminRagControllerComponentTest {

  private MockMvc mockMvc;

  /** 可控 stub，返回预设 AdminRagResult. */
  private AdminRagResult stubResult;

  @BeforeEach
  void setUp() {
    AdminRagService stubService =
        new AdminRagService() {
          @Override
          public AdminRagResult ingest(String title, String content, Map<String, Object> metadata) {
            return stubResult;
          }
        };

    AdminRagController controller = new AdminRagController();
    try {
      var field = AdminRagController.class.getDeclaredField("adminRagService");
      field.setAccessible(true);
      field.set(controller, stubService);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();

    mockMvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .setValidator(validator)
            .build();
  }

  @Test
  @DisplayName("successful upload returns AdminRagResponse with correct fields")
  void successfulUpload() throws Exception {
    stubResult = new AdminRagResult("abc-123", 5, "PENDING");

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
  @DisplayName("response JSON has only three top-level fields: success, code, result")
  void responseHasThreeFields() throws Exception {
    stubResult = new AdminRagResult("id", 1, "PENDING");

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

    // 验证没有 message 字段
    org.junit.jupiter.api.Assertions.assertFalse(
        content.contains("\"message\""), "Response should not contain 'message' field");
  }

  @Test
  @DisplayName("response result has only three business fields: docId, chunks, status")
  void resultHasThreeBusinessFields() throws Exception {
    stubResult = new AdminRagResult("id", 3, "PENDING");

    String body =
        """
        {"title": "T", "content": "C"}""";

    mockMvc
        .perform(post("/api/v1/admin/rag").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result.docId").exists())
        .andExpect(jsonPath("$.result.chunks").exists())
        .andExpect(jsonPath("$.result.status").exists());
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
