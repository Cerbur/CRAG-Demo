package ai.cerbur.crag.console.tenant.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ai.cerbur.crag.console.advice.GlobalExceptionHandler;
import ai.cerbur.crag.console.auth.dto.TenantSummaryResponse;
import ai.cerbur.crag.console.auth.service.AccessIdentityClient;
import ai.cerbur.crag.console.security.filter.BearerTokenAuthenticationFilter;
import ai.cerbur.crag.console.security.jwt.ConsolePrincipal;
import ai.cerbur.crag.console.tenant.dto.TenantListResponse;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

/**
 * TenantController HTTP 契约 MockMvc 测试（plan_21/21.7）。
 *
 * <p>standaloneSetup 装配真实 Controller + GlobalExceptionHandler；AccessIdentityClient 使用 Mockito 替身。锁定
 * GET /api/v1/tenants 路由、状态码、分页与 401。actor userId 只来自 ConsolePrincipal，请求体不携带。
 */
class TenantControllerWebMvcTest {

  private MockMvc mvc;
  private final ObjectMapper om = new ObjectMapper();
  private AccessIdentityClient identityClient;

  @BeforeEach
  void setUp() {
    identityClient = mock(AccessIdentityClient.class);
    TenantController controller = new TenantController(identityClient);
    mvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  @DisplayName("GET /api/v1/tenants 无 Principal → 401 UNAUTHENTICATED")
  void listTenantsUnauthenticatedReturns401() throws Exception {
    mvc.perform(get("/api/v1/tenants").accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(40101));
  }

  @Test
  @DisplayName("GET /api/v1/tenants 携带 Principal → 200，返回 items + nextPageToken")
  void listTenantsReturnsPaginatedItems() throws Exception {
    when(identityClient.listTenantsPage(eq(123L), eq(20), eq("")))
        .thenReturn(
            new AccessIdentityClient.TenantsPage(
                List.of(
                    new TenantSummaryResponse("1", "alice 的空间", "OWNER"),
                    new TenantSummaryResponse("7", "shared", "MEMBER")),
                "7"));

    mvc.perform(
            get("/api/v1/tenants")
                .param("pageSize", "20")
                .accept(MediaType.APPLICATION_JSON)
                .requestAttr(
                    BearerTokenAuthenticationFilter.PRINCIPAL_ATTR,
                    new ConsolePrincipal(123L, 456L)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.result.items[0].tenantId").value("1"))
        .andExpect(jsonPath("$.result.items[0].name").value("alice 的空间"))
        .andExpect(jsonPath("$.result.items[0].role").value("OWNER"))
        .andExpect(jsonPath("$.result.items[1].role").value("MEMBER"))
        .andExpect(jsonPath("$.result.nextPageToken").value("7"));

    // 只传 principal userId；不接受 body actorUserId
    verify(identityClient).listTenantsPage(123L, 20, "");
  }

  @Test
  @DisplayName("GET /api/v1/tenants 带 pageToken → 200，参数透传")
  void listTenantsPassesPageToken() throws Exception {
    when(identityClient.listTenantsPage(eq(123L), anyInt(), eq("7")))
        .thenReturn(new AccessIdentityClient.TenantsPage(List.of(), null));

    mvc.perform(
            get("/api/v1/tenants")
                .param("pageToken", "7")
                .accept(MediaType.APPLICATION_JSON)
                .requestAttr(
                    BearerTokenAuthenticationFilter.PRINCIPAL_ATTR,
                    new ConsolePrincipal(123L, 456L)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result.nextPageToken").doesNotExist());

    verify(identityClient).listTenantsPage(123L, 20, "7");
  }

  @Test
  @DisplayName("GET /api/v1/tenants 非法 pageSize → 400 INVALID_ARGUMENT")
  void listTenantsInvalidPageSizeReturns400() throws Exception {
    mvc.perform(
            get("/api/v1/tenants")
                .param("pageSize", "0")
                .accept(MediaType.APPLICATION_JSON)
                .requestAttr(
                    BearerTokenAuthenticationFilter.PRINCIPAL_ATTR,
                    new ConsolePrincipal(123L, 456L)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(40002));
  }

  @Test
  @DisplayName("GET /api/v1/tenants 下游不可用 → 503 DOWNSTREAM_UNAVAILABLE")
  void listTenantsDownstreamUnavailableReturns503() throws Exception {
    when(identityClient.listTenantsPage(anyLong(), anyInt(), any()))
        .thenThrow(new AccessIdentityClient.DownstreamUnavailableException());

    mvc.perform(
            get("/api/v1/tenants")
                .accept(MediaType.APPLICATION_JSON)
                .requestAttr(
                    BearerTokenAuthenticationFilter.PRINCIPAL_ATTR,
                    new ConsolePrincipal(123L, 456L)))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value(50301));
  }

  // 确认 TenantListResponse 字段结构稳定（编译期断言）
  @Test
  @DisplayName("TenantListResponse record 结构稳定（items / nextPageToken）")
  void tenantListResponseStructure() {
    TenantListResponse resp = new TenantListResponse(List.of(), null);
    org.junit.jupiter.api.Assertions.assertNotNull(resp.items());
    org.junit.jupiter.api.Assertions.assertNull(resp.nextPageToken());
  }
}
