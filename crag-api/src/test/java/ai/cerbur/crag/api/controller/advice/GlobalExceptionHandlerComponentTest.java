package ai.cerbur.crag.api.controller.advice;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/**
 * 轻量组件测试 —— 验证 GlobalExceptionHandler 将四类异常正确转换为业务码与 HTTP 状态.
 *
 * <p>使用 MockMvcBuilders.standaloneSetup 只加载目标 Controller 与 Advice，不启动完整 Spring 上下文。 通过 test source
 * set 中的 TestExceptionController 触发 IllegalArgumentException 与兜底分支。 手动设置 Validator 以启用 Jakarta Bean
 * Validation。
 */
class GlobalExceptionHandlerComponentTest {

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();

    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new TestExceptionController(),
                new ai.cerbur.crag.api.controller.AdminRagController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .setValidator(validator)
            .build();
  }

  // ═══════════════════════════════════════════════════════════════
  // Bean Validation → VALIDATION_ERROR / HTTP 400
  // ═══════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("Bean Validation failures")
  class BeanValidation {

    @Test
    @DisplayName("missing title returns VALIDATION_ERROR with HTTP 400")
    void missingTitle() throws Exception {
      String body =
          """
          {"content": "some content"}""";

      mockMvc
          .perform(post("/api/v1/admin/rag").contentType(MediaType.APPLICATION_JSON).content(body))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.success").value(false))
          .andExpect(jsonPath("$.code").value(40001))
          .andExpect(jsonPath("$.result").isEmpty());
    }

    @Test
    @DisplayName("missing content returns VALIDATION_ERROR with HTTP 400")
    void missingContent() throws Exception {
      String body =
          """
          {"title": "some title"}""";

      mockMvc
          .perform(post("/api/v1/admin/rag").contentType(MediaType.APPLICATION_JSON).content(body))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.success").value(false))
          .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    @DisplayName("empty body returns VALIDATION_ERROR with HTTP 400")
    void emptyBody() throws Exception {
      mockMvc
          .perform(post("/api/v1/admin/rag").contentType(MediaType.APPLICATION_JSON).content("{}"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.success").value(false))
          .andExpect(jsonPath("$.code").value(40001));
    }
  }

  // ═══════════════════════════════════════════════════════════════
  // IllegalArgumentException → INVALID_ARGUMENT / HTTP 400
  // ═══════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("IllegalArgumentException mapping")
  class IllegalArgument {

    @Test
    @DisplayName("returns INVALID_ARGUMENT with HTTP 400")
    void returnsInvalidArgument() throws Exception {
      mockMvc
          .perform(get("/api/v1/test/exception/illegal-argument"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.success").value(false))
          .andExpect(jsonPath("$.code").value(40002))
          .andExpect(jsonPath("$.result").isEmpty());
    }
  }

  // ═══════════════════════════════════════════════════════════════
  // NoResourceFoundException → NOT_FOUND / HTTP 404
  // ═══════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("NoResourceFoundException mapping")
  class NotFound {

    @Test
    @DisplayName("returns NOT_FOUND with HTTP 404")
    void returnsNotFound() throws Exception {
      mockMvc
          .perform(get("/api/v1/test/exception/not-found"))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.success").value(false))
          .andExpect(jsonPath("$.code").value(40401))
          .andExpect(jsonPath("$.result").isEmpty());
    }
  }

  // ═══════════════════════════════════════════════════════════════
  // Unhandled exception → INTERNAL_ERROR / HTTP 500
  // ═══════════════════════════════════════════════════════════════

  @Nested
  @DisplayName("Fallback exception mapping")
  class Fallback {

    @Test
    @DisplayName("returns INTERNAL_ERROR with HTTP 500")
    void returnsInternalError() throws Exception {
      mockMvc
          .perform(get("/api/v1/test/exception/internal-error"))
          .andExpect(status().isInternalServerError())
          .andExpect(jsonPath("$.success").value(false))
          .andExpect(jsonPath("$.code").value(50001))
          .andExpect(jsonPath("$.result").isEmpty());
    }
  }
}
