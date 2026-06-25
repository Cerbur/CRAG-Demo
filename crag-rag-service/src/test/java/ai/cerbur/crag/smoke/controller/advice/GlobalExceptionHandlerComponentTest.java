package ai.cerbur.crag.smoke.controller.advice;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 轻量组件测试 —— 验证 GlobalExceptionHandler 将四类异常正确转换为业务码与 HTTP 状态.
 *
 * <p>使用 @WebMvcTest 加载 MVC 切片，通过 @Import 显式注册 TestExceptionController 和 GlobalExceptionHandler。
 */
@ActiveProfiles("smoke")
@WebMvcTest
@Import({TestExceptionController.class, GlobalExceptionHandler.class})
class GlobalExceptionHandlerComponentTest {

  @Autowired private MockMvc mockMvc;

  @Nested
  @DisplayName("IllegalArgumentException mapping")
  class IllegalArgument {

    @Test
    @DisplayName("returns INVALID_ARGUMENT with HTTP 400")
    void returnsInvalidArgument() throws Exception {
      mockMvc
          .perform(get("/api/v1/smoke/test/exception/illegal-argument"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.success").value(false))
          .andExpect(jsonPath("$.code").value(40002))
          .andExpect(jsonPath("$.result").isEmpty());
    }
  }

  @Nested
  @DisplayName("NoResourceFoundException mapping")
  class NotFound {

    @Test
    @DisplayName("returns NOT_FOUND with HTTP 404")
    void returnsNotFound() throws Exception {
      mockMvc
          .perform(get("/api/v1/smoke/test/exception/not-found"))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.success").value(false))
          .andExpect(jsonPath("$.code").value(40401))
          .andExpect(jsonPath("$.result").isEmpty());
    }
  }

  @Nested
  @DisplayName("Fallback exception mapping")
  class Fallback {

    @Test
    @DisplayName("returns INTERNAL_ERROR with HTTP 500")
    void returnsInternalError() throws Exception {
      mockMvc
          .perform(get("/api/v1/smoke/test/exception/internal-error"))
          .andExpect(status().isInternalServerError())
          .andExpect(jsonPath("$.success").value(false))
          .andExpect(jsonPath("$.code").value(50001))
          .andExpect(jsonPath("$.result").isEmpty());
    }
  }
}
