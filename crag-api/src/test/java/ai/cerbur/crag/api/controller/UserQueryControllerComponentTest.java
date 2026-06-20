package ai.cerbur.crag.api.controller;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.cerbur.crag.api.controller.advice.GlobalExceptionHandler;
import ai.cerbur.crag.query.api.InvalidQueryException;
import ai.cerbur.crag.query.api.LlmUnavailableException;
import ai.cerbur.crag.query.api.QuerySource;
import ai.cerbur.crag.query.api.UserQueryResult;
import ai.cerbur.crag.query.api.UserQueryService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 轻量组件测试 —— 验证 UserQueryController 成功映射与异常处理.
 *
 * <p>使用 @WebMvcTest 只加载 MVC 切片。通过 @Import 提供控制器、GlobalExceptionHandler 和 mock StubConfig。
 */
@WebMvcTest
@Import({
  UserQueryController.class,
  GlobalExceptionHandler.class,
  UserQueryControllerComponentTest.StubConfig.class
})
class UserQueryControllerComponentTest {

  @Autowired private MockMvc mockMvc;

  @Autowired private UserQueryService userQueryService;

  @BeforeEach
  void resetMock() {
    reset(userQueryService);
  }

  @Configuration
  static class StubConfig {

    @Bean
    UserQueryService userQueryService() {
      return mock(UserQueryService.class);
    }
  }

  @Nested
  @DisplayName("Success scenarios")
  class Success {

    @Test
    @DisplayName("valid question returns 200 with answer and sources")
    void validQuestionReturnsSuccess() throws Exception {
      QuerySource source = new QuerySource("S1", "parent-1", List.of("child-1", "child-2"));
      when(userQueryService.answer("test question"))
          .thenReturn(new UserQueryResult("This is the answer.", List.of(source)));

      mockMvc
          .perform(
              post("/api/v1/query")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"question\": \"test question\"}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.success").value(true))
          .andExpect(jsonPath("$.code").value(0))
          .andExpect(jsonPath("$.result.answer").value("This is the answer."))
          .andExpect(jsonPath("$.result.sources[0].reference").value("S1"))
          .andExpect(jsonPath("$.result.sources[0].parentChunkId").value("parent-1"))
          .andExpect(jsonPath("$.result.sources[0].matchedChildIds[0]").value("child-1"))
          .andExpect(jsonPath("$.result.sources[0].matchedChildIds[1]").value("child-2"));
    }

    @Test
    @DisplayName("evidence insufficient returns 200 with empty sources")
    void evidenceInsufficientReturnsSuccess() throws Exception {
      when(userQueryService.answer("unknown question"))
          .thenReturn(new UserQueryResult("知识库证据不足", List.of()));

      mockMvc
          .perform(
              post("/api/v1/query")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"question\": \"unknown question\"}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.success").value(true))
          .andExpect(jsonPath("$.code").value(0))
          .andExpect(jsonPath("$.result.answer").value("知识库证据不足"))
          .andExpect(jsonPath("$.result.sources").isArray())
          .andExpect(jsonPath("$.result.sources").isEmpty());
    }

    @Test
    @DisplayName("unknown JSON fields are ignored")
    void unknownFieldsAreIgnored() throws Exception {
      when(userQueryService.answer("test")).thenReturn(new UserQueryResult("answer", List.of()));

      mockMvc
          .perform(
              post("/api/v1/query")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"question\": \"test\", \"extraField\": \"should be ignored\"}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.success").value(true))
          .andExpect(jsonPath("$.code").value(0));
    }
  }

  @Nested
  @DisplayName("Validation failure scenarios")
  class Validation {

    @Test
    @DisplayName("blank question returns 400 with VALIDATION_ERROR")
    void blankQuestionReturnsBadRequest() throws Exception {
      mockMvc
          .perform(
              post("/api/v1/query")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"question\": \"\"}"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.success").value(false))
          .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    @DisplayName("whitespace-only question returns 400 after trim")
    void whitespaceOnlyReturnsBadRequest() throws Exception {
      mockMvc
          .perform(
              post("/api/v1/query")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"question\": \"   \"}"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.success").value(false))
          .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    @DisplayName("question exceeding 2000 characters returns 400")
    void tooLongQuestionReturnsBadRequest() throws Exception {
      String longQuestion = "a".repeat(2001);
      mockMvc
          .perform(
              post("/api/v1/query")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"question\": \"" + longQuestion + "\"}"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.success").value(false))
          .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    @DisplayName("missing question field returns 400")
    void missingQuestionReturnsBadRequest() throws Exception {
      mockMvc
          .perform(post("/api/v1/query").contentType(MediaType.APPLICATION_JSON).content("{}"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.success").value(false))
          .andExpect(jsonPath("$.code").value(40001));
    }
  }

  @Nested
  @DisplayName("Exception mapping scenarios")
  class ExceptionMapping {

    @Test
    @DisplayName("InvalidQueryException returns 400 with VALIDATION_ERROR")
    void invalidQueryReturnsBadRequest() throws Exception {
      when(userQueryService.answer(anyString()))
          .thenThrow(
              new InvalidQueryException(
                  InvalidQueryException.Reason.QUESTION_REQUIRED, "Question must not be blank"));

      mockMvc
          .perform(
              post("/api/v1/query")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"question\": \"valid but service rejects\"}"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.success").value(false))
          .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    @DisplayName("LlmUnavailableException returns 502 with LLM_UNAVAILABLE")
    void llmUnavailableReturns502() throws Exception {
      when(userQueryService.answer(anyString()))
          .thenThrow(
              new LlmUnavailableException(
                  "LLM provider failure", new RuntimeException("connection refused"), "deepseek"));

      mockMvc
          .perform(
              post("/api/v1/query")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"question\": \"test\"}"))
          .andExpect(status().is(502))
          .andExpect(jsonPath("$.success").value(false))
          .andExpect(jsonPath("$.code").value(50201));
    }

    @Test
    @DisplayName("RuntimeException returns 500 with INTERNAL_ERROR")
    void runtimeExceptionReturns500() throws Exception {
      when(userQueryService.answer(anyString()))
          .thenThrow(new RuntimeException("unexpected error"));

      mockMvc
          .perform(
              post("/api/v1/query")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"question\": \"test\"}"))
          .andExpect(status().isInternalServerError())
          .andExpect(jsonPath("$.success").value(false))
          .andExpect(jsonPath("$.code").value(50001));
    }
  }
}
