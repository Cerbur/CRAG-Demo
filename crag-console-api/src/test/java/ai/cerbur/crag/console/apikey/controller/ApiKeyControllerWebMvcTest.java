package ai.cerbur.crag.console.apikey.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ai.cerbur.crag.console.advice.GlobalExceptionHandler;
import ai.cerbur.crag.console.apikey.dto.ApiKeyListResponse;
import ai.cerbur.crag.console.apikey.dto.ApiKeyResponse;
import ai.cerbur.crag.console.apikey.dto.CreateApiKeyRequest;
import ai.cerbur.crag.console.apikey.dto.CreatedApiKeyResponse;
import ai.cerbur.crag.console.apikey.service.ApiKeyOrchestrator;
import ai.cerbur.crag.console.apikey.service.ApiKeyOrchestrator.ConflictException;
import ai.cerbur.crag.console.apikey.service.ApiKeyOrchestrator.DownstreamUnavailableException;
import ai.cerbur.crag.console.apikey.service.ApiKeyOrchestrator.ForbiddenException;
import ai.cerbur.crag.console.apikey.service.ApiKeyOrchestrator.NotFoundException;
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
 * ApiKeyController HTTP 契约 MockMvc 测试（plan_21/21.9）。
 *
 * <p>standaloneSetup 装配真实 Controller + GlobalExceptionHandler；ApiKeyOrchestrator 使用 Mockito 替身。
 * 锁定七个 operation 的路由、状态码、JSON 字段、负向映射（401/403/404/409/503）、completeKey 一次性返回与前缀列表。
 */
class ApiKeyControllerWebMvcTest {

  private MockMvc mvc;
  private final ObjectMapper om = new ObjectMapper();
  private ApiKeyOrchestrator orchestrator;

  private static final String BASE = "/api/v1/tenants/1/knowledge-bases/100/api-keys";
  private static final ConsolePrincipal PRINCIPAL = new ConsolePrincipal(123L, 456L);

  @BeforeEach
  void setUp() {
    orchestrator = mock(ApiKeyOrchestrator.class);
    ApiKeyController controller = new ApiKeyController(orchestrator);
    mvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  // ---- list ----

  @Test
  @DisplayName("GET /api-keys 无 Principal → 401")
  void listUnauthenticatedReturns401() throws Exception {
    mvc.perform(get(BASE).accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(40101));
  }

  @Test
  @DisplayName("GET /api-keys → 200，items 仅含 keyPrefix，不含 completeKey")
  void listReturnsPrefixOnly() throws Exception {
    when(orchestrator.list(eq(123L), eq(1L), eq(100L), eq(20), eq("")))
        .thenReturn(
            new ApiKeyListResponse(
                List.of(
                    new ApiKeyResponse(
                        "200",
                        "100",
                        "prod-key",
                        "ACTIVE",
                        "crag_abc",
                        Instant.parse("2026-06-29T00:00:00Z"),
                        Instant.parse("2026-09-29T00:00:00Z"))),
                "200"));

    mvc.perform(
            get(BASE)
                .accept(MediaType.APPLICATION_JSON)
                .requestAttr(BearerTokenAuthenticationFilter.PRINCIPAL_ATTR, PRINCIPAL))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result.items[0].apiKeyId").value("200"))
        .andExpect(jsonPath("$.result.items[0].keyPrefix").value("crag_abc"))
        // 列表项不得有 completeKey 字段
        .andExpect(jsonPath("$.result.items[0].completeKey").doesNotExist())
        .andExpect(jsonPath("$.result.nextPageToken").value("200"));

    verify(orchestrator).list(123L, 1L, 100L, 20, "");
  }

  @Test
  @DisplayName("GET /api-keys MEMBER 越权 → 403")
  void listMemberForbiddenReturns403() throws Exception {
    when(orchestrator.list(anyLong(), anyLong(), anyLong(), anyInt(), any()))
        .thenThrow(new ForbiddenException());
    mvc.perform(
            get(BASE)
                .accept(MediaType.APPLICATION_JSON)
                .requestAttr(BearerTokenAuthenticationFilter.PRINCIPAL_ATTR, PRINCIPAL))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(40301));
  }

  @Test
  @DisplayName("GET /api-keys 跨 KB → 404（不泄漏存在性）")
  void listCrossKbReturns404() throws Exception {
    when(orchestrator.list(anyLong(), anyLong(), anyLong(), anyInt(), any()))
        .thenThrow(new NotFoundException());
    mvc.perform(
            get("/api/v1/tenants/1/knowledge-bases/999/api-keys")
                .accept(MediaType.APPLICATION_JSON)
                .requestAttr(BearerTokenAuthenticationFilter.PRINCIPAL_ATTR, PRINCIPAL))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(40401));
  }

  // ---- create ----

  @Test
  @DisplayName("POST /api-keys → 201，含一次性 completeKey")
  void createReturns201WithCompleteKey() throws Exception {
    when(orchestrator.create(eq(123L), eq(1L), eq(100L), eq("prod-key"), anyLong()))
        .thenReturn(
            new CreatedApiKeyResponse(
                "200",
                "100",
                "prod-key",
                "crag_abc_secretvalue",
                Instant.parse("2026-09-29T00:00:00Z")));

    mvc.perform(
            post(BASE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(new CreateApiKeyRequest("prod-key", 0L)))
                .requestAttr(BearerTokenAuthenticationFilter.PRINCIPAL_ATTR, PRINCIPAL))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.result.apiKeyId").value("200"))
        .andExpect(jsonPath("$.result.completeKey").value("crag_abc_secretvalue"));

    verify(orchestrator).create(123L, 1L, 100L, "prod-key", 0L);
  }

  @Test
  @DisplayName("POST /api-keys name 校验失败 → 400 VALIDATION_ERROR（不回显）")
  void createValidationReturns400() throws Exception {
    mvc.perform(
            post(BASE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\"}")
                .requestAttr(BearerTokenAuthenticationFilter.PRINCIPAL_ATTR, PRINCIPAL))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(40001));
  }

  @Test
  @DisplayName("POST /api-keys MEMBER 越权 → 403")
  void createMemberForbiddenReturns403() throws Exception {
    when(orchestrator.create(anyLong(), anyLong(), anyLong(), any(), anyLong()))
        .thenThrow(new ForbiddenException());
    mvc.perform(
            post(BASE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(new CreateApiKeyRequest("k", 0L)))
                .requestAttr(BearerTokenAuthenticationFilter.PRINCIPAL_ATTR, PRINCIPAL))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("POST /api-keys 下游不可用 → 503")
  void createDownstreamUnavailableReturns503() throws Exception {
    when(orchestrator.create(anyLong(), anyLong(), anyLong(), any(), anyLong()))
        .thenThrow(new DownstreamUnavailableException());
    mvc.perform(
            post(BASE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(new CreateApiKeyRequest("k", 0L)))
                .requestAttr(BearerTokenAuthenticationFilter.PRINCIPAL_ATTR, PRINCIPAL))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value(50301));
  }

  // ---- get ----

  @Test
  @DisplayName("GET /api-keys/{id} → 200，前缀投影（不含 completeKey）")
  void getReturnsPrefixOnly() throws Exception {
    when(orchestrator.get(eq(123L), eq(1L), eq(100L), eq(200L)))
        .thenReturn(
            new ApiKeyResponse(
                "200",
                "100",
                "prod-key",
                "ACTIVE",
                "crag_abc",
                Instant.parse("2026-06-29T00:00:00Z"),
                Instant.parse("2026-09-29T00:00:00Z")));
    mvc.perform(
            get(BASE + "/200")
                .accept(MediaType.APPLICATION_JSON)
                .requestAttr(BearerTokenAuthenticationFilter.PRINCIPAL_ATTR, PRINCIPAL))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result.apiKeyId").value("200"))
        .andExpect(jsonPath("$.result.keyPrefix").value("crag_abc"))
        .andExpect(jsonPath("$.result.completeKey").doesNotExist());
  }

  @Test
  @DisplayName("GET /api-keys/{id} 不存在/跨 KB → 404")
  void getNotFoundReturns404() throws Exception {
    when(orchestrator.get(anyLong(), anyLong(), anyLong(), anyLong()))
        .thenThrow(new NotFoundException());
    mvc.perform(
            get(BASE + "/999")
                .accept(MediaType.APPLICATION_JSON)
                .requestAttr(BearerTokenAuthenticationFilter.PRINCIPAL_ATTR, PRINCIPAL))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(40401));
  }

  // ---- disable/enable/revoke ----

  @Test
  @DisplayName("POST /api-keys/{id}/disable → 200 DISABLED")
  void disableReturns200() throws Exception {
    when(orchestrator.disable(eq(123L), eq(1L), eq(100L), eq(200L)))
        .thenReturn(
            new ApiKeyResponse(
                "200",
                "100",
                "k",
                "DISABLED",
                "crag_abc",
                Instant.parse("2026-06-29T00:00:00Z"),
                Instant.parse("2026-09-29T00:00:00Z")));
    mvc.perform(
            post(BASE + "/200/disable")
                .requestAttr(BearerTokenAuthenticationFilter.PRINCIPAL_ATTR, PRINCIPAL))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result.status").value("DISABLED"));
  }

  @Test
  @DisplayName("POST /api-keys/{id}/disable 已 DISABLED → 409 CONFLICT")
  void disableConflictReturns409() throws Exception {
    when(orchestrator.disable(anyLong(), anyLong(), anyLong(), anyLong()))
        .thenThrow(new ConflictException());
    mvc.perform(
            post(BASE + "/200/disable")
                .requestAttr(BearerTokenAuthenticationFilter.PRINCIPAL_ATTR, PRINCIPAL))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(40901));
  }

  @Test
  @DisplayName("POST /api-keys/{id}/enable → 200 ACTIVE")
  void enableReturns200() throws Exception {
    when(orchestrator.enable(eq(123L), eq(1L), eq(100L), eq(200L)))
        .thenReturn(
            new ApiKeyResponse(
                "200",
                "100",
                "k",
                "ACTIVE",
                "crag_abc",
                Instant.parse("2026-06-29T00:00:00Z"),
                Instant.parse("2026-09-29T00:00:00Z")));
    mvc.perform(
            post(BASE + "/200/enable")
                .requestAttr(BearerTokenAuthenticationFilter.PRINCIPAL_ATTR, PRINCIPAL))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result.status").value("ACTIVE"));
  }

  @Test
  @DisplayName("POST /api-keys/{id}/revoke → 200 REVOKED")
  void revokeReturns200() throws Exception {
    when(orchestrator.revoke(eq(123L), eq(1L), eq(100L), eq(200L)))
        .thenReturn(
            new ApiKeyResponse(
                "200",
                "100",
                "k",
                "REVOKED",
                "crag_abc",
                Instant.parse("2026-06-29T00:00:00Z"),
                Instant.parse("2026-09-29T00:00:00Z")));
    mvc.perform(
            post(BASE + "/200/revoke")
                .requestAttr(BearerTokenAuthenticationFilter.PRINCIPAL_ATTR, PRINCIPAL))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result.status").value("REVOKED"));
  }

  @Test
  @DisplayName("POST /api-keys/{id}/revoke 已 REVOKED → 409 CONFLICT")
  void revokeConflictReturns409() throws Exception {
    when(orchestrator.revoke(anyLong(), anyLong(), anyLong(), anyLong()))
        .thenThrow(new ConflictException());
    mvc.perform(
            post(BASE + "/200/revoke")
                .requestAttr(BearerTokenAuthenticationFilter.PRINCIPAL_ATTR, PRINCIPAL))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(40901));
  }

  // ---- rotate ----

  @Test
  @DisplayName("POST /api-keys/{id}/rotate → 200，含一次性新 completeKey")
  void rotateReturnsNewSecret() throws Exception {
    when(orchestrator.rotate(eq(123L), eq(1L), eq(100L), eq(200L), anyLong()))
        .thenReturn(
            new CreatedApiKeyResponse(
                "201", "100", "k", "crag_xyz_newsecret", Instant.parse("2026-09-29T00:00:00Z")));
    mvc.perform(
            post(BASE + "/200/rotate")
                .requestAttr(BearerTokenAuthenticationFilter.PRINCIPAL_ATTR, PRINCIPAL))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result.apiKeyId").value("201"))
        .andExpect(jsonPath("$.result.completeKey").value("crag_xyz_newsecret"));
  }

  @Test
  @DisplayName("POST /api-keys/{id}/rotate 已 DISABLED/REVOKED → 409 CONFLICT")
  void rotateConflictReturns409() throws Exception {
    when(orchestrator.rotate(anyLong(), anyLong(), anyLong(), anyLong(), anyLong()))
        .thenThrow(new ConflictException());
    mvc.perform(
            post(BASE + "/200/rotate")
                .requestAttr(BearerTokenAuthenticationFilter.PRINCIPAL_ATTR, PRINCIPAL))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value(40901));
  }
}
