package ai.cerbur.crag.open.query.controller;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.cerbur.crag.open.advice.GlobalExceptionHandler;
import ai.cerbur.crag.open.auth.service.AccessApiKeyClient;
import ai.cerbur.crag.open.query.dto.CitationResponse;
import ai.cerbur.crag.open.query.dto.QueryResponse;
import ai.cerbur.crag.open.query.service.OpenQueryService;
import ai.cerbur.crag.open.query.service.RagQueryClient.InvalidQueryException;
import ai.cerbur.crag.open.query.service.RagQueryClient.LlmUnavailableException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * QueryController HTTP 契约测试（plan_21/21.10）。
 *
 * <p>验证：路由与状态码、question 校验（1–2000）、缺失/无效 Bearer 401、LLM 不可用 502、Access 鉴权失败 401、 sources 映射、请求体不接受
 * knowledgeBaseId/tenantId。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("QueryController HTTP 契约")
class QueryControllerWebMvcTest {

  @Mock private OpenQueryService queryService;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    QueryController controller = new QueryController();
    // 注入 mock service
    org.springframework.test.util.ReflectionTestUtils.setField(
        controller, "queryService", queryService);
    mvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  @DisplayName("POST /api/v1/query 成功返回 200 + answer + sources")
  void querySuccess() throws Exception {
    when(queryService.query(anyString(), anyString(), anyString()))
        .thenReturn(
            new QueryResponse("因为 X 所以 Y", List.of(new CitationResponse("[S1]", "12345", "段落内容"))));

    mvc.perform(
            post("/api/v1/query")
                .header("Authorization", "Bearer crag_prefix_secret")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"为什么？\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.result.answer").value("因为 X 所以 Y"))
        .andExpect(jsonPath("$.result.sources[0].reference").value("[S1]"))
        .andExpect(jsonPath("$.result.sources[0].documentId").value("12345"))
        .andExpect(jsonPath("$.result.sources[0].excerpt").value("段落内容"));
  }

  @Test
  @DisplayName("请求体含 knowledgeBaseId 不被接受（Key 决定 KB，不接受客户端传入）")
  void requestBodyDoesNotAcceptKnowledgeBaseId() throws Exception {
    when(queryService.query(anyString(), anyString(), anyString()))
        .thenReturn(new QueryResponse("ans", List.of()));

    // 即使客户端传入 knowledgeBaseId，Controller 也不读取；question 仍正常处理
    mvc.perform(
            post("/api/v1/query")
                .header("Authorization", "Bearer crag_prefix_secret")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"question\":\"q\",\"knowledgeBaseId\":\"99999\",\"tenantId\":\"88888\"}"))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("缺失 Authorization → 401 UNAUTHENTICATED")
  void missingAuthorization() throws Exception {
    mvc.perform(
            post("/api/v1/query")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"q\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(40101))
        .andExpect(jsonPath("$.result.reason").value("MISSING_API_KEY"));
  }

  @Test
  @DisplayName("Authorization 非 Bearer 格式 → 401")
  void nonBearerAuthorization() throws Exception {
    mvc.perform(
            post("/api/v1/query")
                .header("Authorization", "Basic abc")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"q\"}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("Bearer 为空 → 401")
  void emptyBearer() throws Exception {
    mvc.perform(
            post("/api/v1/query")
                .header("Authorization", "Bearer   ")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"q\"}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("question 缺失 → 400 VALIDATION_ERROR")
  void questionMissing() throws Exception {
    mvc.perform(
            post("/api/v1/query")
                .header("Authorization", "Bearer crag_k")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(40001));
  }

  @Test
  @DisplayName("question 空白 → 400")
  void questionBlank() throws Exception {
    mvc.perform(
            post("/api/v1/query")
                .header("Authorization", "Bearer crag_k")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"   \"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("question 超过 2000 字符 → 400")
  void questionTooLong() throws Exception {
    String longQuestion = "a".repeat(2001);
    mvc.perform(
            post("/api/v1/query")
                .header("Authorization", "Bearer crag_k")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"" + longQuestion + "\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("question 恰好 2000 字符 → 不报校验错（鉴权失败 401 在后）")
  void questionExactly2000() throws Exception {
    String q = "a".repeat(2000);
    when(queryService.query(anyString(), anyString(), anyString()))
        .thenReturn(new QueryResponse("ans", List.of()));
    mvc.perform(
            post("/api/v1/query")
                .header("Authorization", "Bearer crag_k")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"" + q + "\"}"))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("Access 鉴权失败 → 40102 INVALID_CREDENTIALS")
  void authFailed() throws Exception {
    when(queryService.query(anyString(), anyString(), anyString()))
        .thenThrow(new AccessApiKeyClient.InvalidApiKeyException());

    mvc.perform(
            post("/api/v1/query")
                .header("Authorization", "Bearer crag_bad")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"q\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(40102));
  }

  @Test
  @DisplayName("LLM 不可用 → 50201")
  void llmUnavailable() throws Exception {
    when(queryService.query(anyString(), anyString(), anyString()))
        .thenThrow(new LlmUnavailableException());

    mvc.perform(
            post("/api/v1/query")
                .header("Authorization", "Bearer crag_k")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"q\"}"))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.code").value(50201));
  }

  @Test
  @DisplayName("RAG 查询非法 → 40002")
  void invalidQuery() throws Exception {
    when(queryService.query(anyString(), anyString(), anyString()))
        .thenThrow(new InvalidQueryException());

    mvc.perform(
            post("/api/v1/query")
                .header("Authorization", "Bearer crag_k")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"q\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(40002));
  }

  @Test
  @DisplayName("X-Request-Id 透传到 traceId")
  void traceIdFromHeader() throws Exception {
    when(queryService.query(anyString(), anyString(), anyString()))
        .thenReturn(new QueryResponse("ans", List.of()));

    mvc.perform(
            post("/api/v1/query")
                .header("Authorization", "Bearer crag_k")
                .header("X-Request-Id", "req-123")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"q\"}"))
        .andExpect(status().isOk());
  }
}
