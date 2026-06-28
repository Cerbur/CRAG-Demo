package ai.cerbur.crag.console.auth.controller;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ai.cerbur.crag.console.advice.GlobalExceptionHandler;
import ai.cerbur.crag.console.auth.dto.AuthResponse;
import ai.cerbur.crag.console.auth.dto.TenantSummaryResponse;
import ai.cerbur.crag.console.auth.dto.UserResponse;
import ai.cerbur.crag.console.auth.service.AccessIdentityClient;
import ai.cerbur.crag.console.auth.service.AccessIdentityClient.DownstreamUnavailableException;
import ai.cerbur.crag.console.auth.service.AccessIdentityClient.InvalidCredentialsException;
import ai.cerbur.crag.console.auth.service.RefreshCookieService;
import ai.cerbur.crag.console.config.ConsoleAuthProperties;
import ai.cerbur.crag.console.security.filter.BearerTokenAuthenticationFilter;
import ai.cerbur.crag.console.security.jwt.ConsolePrincipal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

/**
 * AuthController HTTP 契约 MockMvc 测试（plan_21/21.6）。
 *
 * <p>使用 standaloneSetup 装配真实 Controller + GlobalExceptionHandler；AccessIdentityClient 与
 * RefreshCookieService 通过 Mockito 替身。 不启动 Spring Context，定位 HTTP 路由、状态码、JSON 字段和 Set-Cookie 属性。
 */
class AuthControllerWebMvcTest {

  private MockMvc mvc;
  private final ObjectMapper om = new ObjectMapper();
  private AccessIdentityClient identityClient;
  private RefreshCookieService cookieService;
  private ConsoleAuthProperties authProps;

  @BeforeEach
  void setUp() {
    identityClient = mock(AccessIdentityClient.class);
    cookieService = mock(RefreshCookieService.class);
    authProps = new ConsoleAuthProperties();
    authProps.setAllowedOrigins(Set.of("https://console.example.com", "http://localhost:8080"));
    authProps.setCookie(new ConsoleAuthProperties.Cookie());
    authProps.getCookie().setSecure(false);
    authProps.getCookie().setRefreshTtl(Duration.ofSeconds(3600));
    AuthController controller = new AuthController(identityClient, cookieService, authProps);
    mvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  @DisplayName(
      "POST /api/v1/auth/register → 200，body 含 accessToken/user/defaultTenant；不含 refreshToken")
  void registerReturnsAccessAndDefaultTenant() throws Exception {
    AuthResponse resp =
        new AuthResponse(
            "access-jwt",
            Instant.parse("2026-06-29T00:15:00Z"),
            new UserResponse("123", "alice"),
            null);
    when(identityClient.register("alice", "alice", "alice-password-123"))
        .thenReturn(new AccessIdentityClient.TokenMaterial(resp, "rt-raw"));
    when(identityClient.listTenants(eq(123L), anyInt(), any()))
        .thenReturn(List.of(new TenantSummaryResponse("1", "default", "OWNER")));
    when(cookieService.bake(eq("rt-raw"), any(Duration.class))).thenReturn(dummyCookie());

    registerBody("alice", "alice", "alice-password-123")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.result.accessToken").value("access-jwt"))
        .andExpect(jsonPath("$.result.user.userId").value("123"))
        .andExpect(jsonPath("$.result.defaultTenant.tenantId").value("1"))
        .andExpect(jsonPath("$.result.refreshToken").doesNotExist())
        .andExpect(header().string("Set-Cookie", containsString("HttpOnly")))
        .andExpect(header().string("Set-Cookie", containsString("Path=/api/v1/auth")))
        .andExpect(header().string("Set-Cookie", containsString("SameSite=Lax")));
  }

  @Test
  @DisplayName("POST /api/v1/auth/login → 200，defaultTenant 不存在")
  void loginReturnsAccessWithoutDefaultTenant() throws Exception {
    AuthResponse resp =
        new AuthResponse(
            "access-jwt",
            Instant.parse("2026-06-29T00:15:00Z"),
            new UserResponse("123", "alice"),
            null);
    when(identityClient.login("alice", "alice-password-123"))
        .thenReturn(new AccessIdentityClient.TokenMaterial(resp, "rt-raw"));
    when(cookieService.bake(anyString(), any())).thenReturn(dummyCookie());

    mvc.perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    om.writeValueAsString(
                        new AuthController.LoginBody("alice", "alice-password-123"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result.defaultTenant").doesNotExist())
        .andExpect(jsonPath("$.result.accessToken").exists());
  }

  @Test
  @DisplayName("POST /api/v1/auth/login 无效凭据 → 401，ErrorDetail 不泄漏原因")
  void loginInvalidCredentialsMaps401() throws Exception {
    when(identityClient.login("alice", "wrong")).thenThrow(new InvalidCredentialsException());
    when(cookieService.bake(anyString(), any())).thenReturn(dummyCookie());

    mvc.perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(new AuthController.LoginBody("alice", "wrong"))))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.code").value(40102))
        .andExpect(jsonPath("$.result.retryable").exists());
  }

  @Test
  @DisplayName("POST /api/v1/auth/refresh 缺失 Cookie → 401")
  void refreshMissingCookieReturns401() throws Exception {
    when(cookieService.read(any())).thenReturn(null);
    mvc.perform(post("/api/v1/auth/refresh").header("Origin", "https://console.example.com"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(40101));
  }

  @Test
  @DisplayName("POST /api/v1/auth/refresh 缺失 Origin/Referer → 403")
  void refreshMissingOriginReturns403() throws Exception {
    when(cookieService.read(any())).thenReturn("rt-raw");
    mvc.perform(post("/api/v1/auth/refresh"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(40301));
  }

  @Test
  @DisplayName("POST /api/v1/auth/logout → 200 且 Set-Cookie 清除 refresh_token")
  void logoutAlwaysClearsCookie() throws Exception {
    when(cookieService.read(any())).thenReturn("rt-raw");
    when(cookieService.clear()).thenReturn(clearCookie());
    doNothing().when(identityClient).logout(anyLong(), anyLong());

    mvc.perform(
            post("/api/v1/auth/logout")
                .header("Cookie", "refresh_token=rt-raw")
                .header("Origin", "https://console.example.com")
                .requestAttr(
                    BearerTokenAuthenticationFilter.PRINCIPAL_ATTR,
                    new ConsolePrincipal(123L, 456L)))
        .andExpect(status().isOk())
        .andExpect(header().string("Set-Cookie", containsString("Max-Age=0")));

    verify(identityClient).logout(123L, 456L);
    verify(cookieService).clear();
  }

  @Test
  @DisplayName("POST /api/v1/auth/logout Access 抛错 → 仍 finally 清 Cookie，状态 503")
  void logoutClearsCookieEvenOnAccessFailure() throws Exception {
    when(cookieService.read(any())).thenReturn("rt-raw");
    when(cookieService.clear()).thenReturn(clearCookie());
    doThrow(new DownstreamUnavailableException()).when(identityClient).logout(anyLong(), anyLong());

    mvc.perform(
            post("/api/v1/auth/logout")
                .header("Cookie", "refresh_token=rt-raw")
                .header("Origin", "https://console.example.com")
                .requestAttr(
                    BearerTokenAuthenticationFilter.PRINCIPAL_ATTR,
                    new ConsolePrincipal(123L, 456L)))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value(50301));
    verify(cookieService).clear();
  }

  @Test
  @DisplayName("GET /api/v1/auth/me 无 Principal → 401")
  void meUnauthenticatedReturns401() throws Exception {
    mvc.perform(get("/api/v1/auth/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(40101));
  }

  @Test
  @DisplayName("GET /api/v1/auth/me 携带 ConsolePrincipal → 200 返回安全投影")
  void meReturnsProfile() throws Exception {
    when(identityClient.getUserProfile(123L)).thenReturn(new UserResponse("123", "alice"));
    mvc.perform(
            get("/api/v1/auth/me")
                .requestAttr(
                    BearerTokenAuthenticationFilter.PRINCIPAL_ATTR,
                    new ConsolePrincipal(123L, 456L)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result.userId").value("123"))
        .andExpect(jsonPath("$.result.nickname").value("alice"));
  }

  @Test
  @DisplayName("register 校验失败 → 400 VALIDATION_ERROR，不回显密码")
  void registerValidationNoPasswordLeak() throws Exception {
    mvc.perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(new AuthController.RegisterBody("a", "u", "short"))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(40001))
        .andExpect(jsonPath("$.result.fieldErrors[0].field").exists())
        .andExpect(
            jsonPath("$.result.fieldErrors[*].rejectedValue")
                .value(org.hamcrest.Matchers.not(containsString("short"))));
  }

  // ---- helpers ----

  private ResultActions registerBody(String nickname, String username, String password)
      throws Exception {
    return mvc.perform(
        post("/api/v1/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                om.writeValueAsString(
                    new AuthController.RegisterBody(nickname, username, password))));
  }

  private ResponseCookie dummyCookie() {
    return ResponseCookie.from("refresh_token", "rt-raw")
        .httpOnly(true)
        .sameSite("Lax")
        .path("/api/v1/auth")
        .maxAge(3600)
        .build();
  }

  private ResponseCookie clearCookie() {
    return ResponseCookie.from("refresh_token", "")
        .httpOnly(true)
        .maxAge(0)
        .path("/api/v1/auth")
        .build();
  }
}
