package ai.cerbur.crag.console.knowledge.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ai.cerbur.crag.console.advice.GlobalExceptionHandler;
import ai.cerbur.crag.console.knowledge.dto.CreateKnowledgeBaseRequest;
import ai.cerbur.crag.console.knowledge.dto.KnowledgeBaseListResponse;
import ai.cerbur.crag.console.knowledge.dto.KnowledgeBaseResponse;
import ai.cerbur.crag.console.knowledge.service.KnowledgeBaseOrchestrator;
import ai.cerbur.crag.console.knowledge.service.KnowledgeBaseOrchestrator.ConflictException;
import ai.cerbur.crag.console.knowledge.service.KnowledgeBaseOrchestrator.CreateResult;
import ai.cerbur.crag.console.knowledge.service.KnowledgeBaseOrchestrator.EnsureScopeFailedException.DownstreamUnavailableException;
import ai.cerbur.crag.console.knowledge.service.KnowledgeBaseOrchestrator.ForbiddenException;
import ai.cerbur.crag.console.knowledge.service.KnowledgeBaseOrchestrator.NotFoundException;
import ai.cerbur.crag.console.security.filter.BearerTokenAuthenticationFilter;
import ai.cerbur.crag.console.security.jwt.ConsolePrincipal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

/**
 * KnowledgeBaseController HTTP 契约 MockMvc 测试（plan_21/21.8）。
 *
 * <p>standaloneSetup 装配真实 Controller + GlobalExceptionHandler；KnowledgeBaseOrchestrator 使用 Mockito
 * 替身。锁定 list/create/get 路由、状态码、部分成功 201/apiKeyReady=false、跨租户 404 不泄漏。
 */
class KnowledgeBaseControllerWebMvcTest {

  private MockMvc mvc;
  private final ObjectMapper om = new ObjectMapper();
  private KnowledgeBaseOrchestrator orchestrator;

  @BeforeEach
  void setUp() {
    orchestrator = mock(KnowledgeBaseOrchestrator.class);
    KnowledgeBaseController controller = new KnowledgeBaseController(orchestrator);
    mvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  @DisplayName("GET /knowledge-bases 无 Principal → 401")
  void listUnauthenticatedReturns401() throws Exception {
    mvc.perform(get("/api/v1/tenants/1/knowledge-bases").accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(40101));
  }

  @Test
  @DisplayName("GET /knowledge-bases → 200，items + nextPageToken")
  void listReturnsPaginated() throws Exception {
    when(orchestrator.list(eq(123L), eq(1L), eq(20), eq("")))
        .thenReturn(
            new KnowledgeBaseListResponse(
                List.of(
                    new KnowledgeBaseResponse(
                        "100",
                        "1",
                        "kb-1",
                        true,
                        Instant.parse("2026-06-29T00:00:00Z"),
                        Instant.parse("2026-06-29T00:00:00Z"))),
                "100"));

    mvc.perform(
            get("/api/v1/tenants/1/knowledge-bases")
                .accept(MediaType.APPLICATION_JSON)
                .requestAttr(
                    BearerTokenAuthenticationFilter.PRINCIPAL_ATTR,
                    new ConsolePrincipal(123L, 456L)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result.items[0].knowledgeBaseId").value("100"))
        .andExpect(jsonPath("$.result.items[0].apiKeyReady").value(true))
        .andExpect(jsonPath("$.result.nextPageToken").value("100"));

    verify(orchestrator).list(123L, 1L, 20, "");
  }

  @Test
  @DisplayName("POST /knowledge-bases 完整成功 → 201 apiKeyReady=true")
  void createFullySucceedsReturns201() throws Exception {
    when(orchestrator.create(eq(123L), eq(1L), eq("alice-kb")))
        .thenReturn(
            new CreateResult(
                new KnowledgeBaseResponse(
                    "100",
                    "1",
                    "alice-kb",
                    true,
                    Instant.parse("2026-06-29T00:00:00Z"),
                    Instant.parse("2026-06-29T00:00:00Z"))));

    mvc.perform(
            post("/api/v1/tenants/1/knowledge-bases")
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(new CreateKnowledgeBaseRequest("alice-kb")))
                .requestAttr(
                    BearerTokenAuthenticationFilter.PRINCIPAL_ATTR,
                    new ConsolePrincipal(123L, 456L)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.result.knowledgeBaseId").value("100"))
        .andExpect(jsonPath("$.result.apiKeyReady").value(true));

    verify(orchestrator).create(123L, 1L, "alice-kb");
  }

  @Test
  @DisplayName("POST /knowledge-bases Scope 部分失败 → 201 apiKeyReady=false（资源已创建）")
  void createPartialSuccessReturns201WithApiKeyReadyFalse() throws Exception {
    when(orchestrator.create(eq(123L), eq(1L), eq("alice-kb")))
        .thenReturn(
            new CreateResult(
                new KnowledgeBaseResponse(
                    "100",
                    "1",
                    "alice-kb",
                    false,
                    Instant.parse("2026-06-29T00:00:00Z"),
                    Instant.parse("2026-06-29T00:00:00Z"))));

    mvc.perform(
            post("/api/v1/tenants/1/knowledge-bases")
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(new CreateKnowledgeBaseRequest("alice-kb")))
                .requestAttr(
                    BearerTokenAuthenticationFilter.PRINCIPAL_ATTR,
                    new ConsolePrincipal(123L, 456L)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.result.knowledgeBaseId").value("100"))
        .andExpect(jsonPath("$.result.apiKeyReady").value(false));

    verify(orchestrator).create(123L, 1L, "alice-kb");
  }

  @Test
  @DisplayName("POST /knowledge-bases 非成员 → 403 FORBIDDEN")
  void createForbiddenReturns403() throws Exception {
    when(orchestrator.create(anyLong(), anyLong(), any())).thenThrow(new ForbiddenException());

    mvc.perform(
            post("/api/v1/tenants/1/knowledge-bases")
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(new CreateKnowledgeBaseRequest("kb")))
                .requestAttr(
                    BearerTokenAuthenticationFilter.PRINCIPAL_ATTR,
                    new ConsolePrincipal(123L, 456L)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(40301));
  }

  @Test
  @DisplayName("POST /knowledge-bases name 校验失败 → 400 VALIDATION_ERROR")
  void createValidationReturns400() throws Exception {
    mvc.perform(
            post("/api/v1/tenants/1/knowledge-bases")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\"}")
                .requestAttr(
                    BearerTokenAuthenticationFilter.PRINCIPAL_ATTR,
                    new ConsolePrincipal(123L, 456L)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(40001));
  }

  @Test
  @DisplayName("POST /knowledge-bases 下游不可用 → 503 DOWNSTREAM_UNAVAILABLE")
  void createDownstreamUnavailableReturns503() throws Exception {
    when(orchestrator.create(anyLong(), anyLong(), any()))
        .thenThrow(new DownstreamUnavailableException());

    mvc.perform(
            post("/api/v1/tenants/1/knowledge-bases")
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(new CreateKnowledgeBaseRequest("kb")))
                .requestAttr(
                    BearerTokenAuthenticationFilter.PRINCIPAL_ATTR,
                    new ConsolePrincipal(123L, 456L)))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value(50301));
  }

  @Test
  @DisplayName("POST /knowledge-bases 冲突 → 409 CONFLICT")
  void createConflictReturns409() throws Exception {
    when(orchestrator.create(anyLong(), anyLong(), any())).thenThrow(new ConflictException());

    mvc.perform(
            post("/api/v1/tenants/1/knowledge-bases")
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(new CreateKnowledgeBaseRequest("kb")))
                .requestAttr(
                    BearerTokenAuthenticationFilter.PRINCIPAL_ATTR,
                    new ConsolePrincipal(123L, 456L)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(40901));
  }

  @Test
  @DisplayName("GET /knowledge-bases/{id} → 200 详情")
  void getReturnsDetail() throws Exception {
    when(orchestrator.get(eq(123L), eq(1L), eq(100L)))
        .thenReturn(
            new KnowledgeBaseResponse(
                "100",
                "1",
                "kb-1",
                true,
                Instant.parse("2026-06-29T00:00:00Z"),
                Instant.parse("2026-06-29T00:00:00Z")));

    mvc.perform(
            get("/api/v1/tenants/1/knowledge-bases/100")
                .accept(MediaType.APPLICATION_JSON)
                .requestAttr(
                    BearerTokenAuthenticationFilter.PRINCIPAL_ATTR,
                    new ConsolePrincipal(123L, 456L)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result.knowledgeBaseId").value("100"));
  }

  @Test
  @DisplayName("GET /knowledge-bases/{id} 跨租户 → 404（不泄漏）")
  void getCrossTenantReturns404() throws Exception {
    when(orchestrator.get(anyLong(), anyLong(), anyLong())).thenThrow(new NotFoundException());

    mvc.perform(
            get("/api/v1/tenants/99/knowledge-bases/100")
                .accept(MediaType.APPLICATION_JSON)
                .requestAttr(
                    BearerTokenAuthenticationFilter.PRINCIPAL_ATTR,
                    new ConsolePrincipal(123L, 456L)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(40401));
  }

  @Test
  @DisplayName("GET list 跨租户 → 404（不泄漏）")
  void listCrossTenantReturns404() throws Exception {
    when(orchestrator.list(anyLong(), eq(99L), anyInt(), any())).thenThrow(new NotFoundException());

    mvc.perform(
            get("/api/v1/tenants/99/knowledge-bases")
                .accept(MediaType.APPLICATION_JSON)
                .requestAttr(
                    BearerTokenAuthenticationFilter.PRINCIPAL_ATTR,
                    new ConsolePrincipal(123L, 456L)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(40401));
  }
}
