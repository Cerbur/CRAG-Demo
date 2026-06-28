package ai.cerbur.crag.console.auth.controller;

import ai.cerbur.crag.common.dto.result.Response;
import ai.cerbur.crag.console.auth.dto.AuthResponse;
import ai.cerbur.crag.console.auth.dto.TenantSummaryResponse;
import ai.cerbur.crag.console.auth.dto.UserResponse;
import ai.cerbur.crag.console.auth.service.AccessIdentityClient;
import ai.cerbur.crag.console.auth.service.InvalidOriginException;
import ai.cerbur.crag.console.auth.service.OriginGuard;
import ai.cerbur.crag.console.auth.service.RefreshCookieService;
import ai.cerbur.crag.console.config.ConsoleAuthProperties;
import ai.cerbur.crag.console.security.filter.BearerTokenAuthenticationFilter;
import ai.cerbur.crag.console.security.jwt.ConsolePrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Console Auth HTTP 入口（plan_21/21.6）。
 *
 * <p>路由：register/login/refresh/logout/me。Access JWT 只进响应体；Refresh Token 只进 HttpOnly
 * Cookie。refresh/logout 校验同站 Origin/Referer；logout 在 finally 中清除 Cookie，无论 Access 结果如何。me 通过 Bearer
 * filter 注入的 {@link ConsolePrincipal} 解析当前用户。
 */
@RestController
@RequestMapping("/api/v1/auth")
@Validated
public class AuthController {

  private final AccessIdentityClient identityClient;
  private final RefreshCookieService cookieService;
  private final ConsoleAuthProperties properties;

  public AuthController(
      AccessIdentityClient identityClient,
      RefreshCookieService cookieService,
      ConsoleAuthProperties properties) {
    this.identityClient = identityClient;
    this.cookieService = cookieService;
    this.properties = properties;
  }

  @PostMapping("/register")
  public ResponseEntity<Response<AuthResponse>> register(
      @Valid @RequestBody RegisterBody body, HttpServletResponse response) {
    AccessIdentityClient.TokenMaterial material =
        identityClient.register(body.nickname(), body.username(), body.password());
    AuthResponse resp = material.response();
    List<TenantSummaryResponse> tenants =
        identityClient.listTenants(parseLong(resp.user().userId()), 1, null);
    AuthResponse withTenant =
        new AuthResponse(
            resp.accessToken(),
            resp.accessExpiresAt(),
            resp.user(),
            tenants.isEmpty() ? null : tenants.get(0));
    response.addHeader(
        "Set-Cookie", cookieService.bake(material.rawRefreshToken(), refreshTtl()).toString());
    return ResponseEntity.ok(Response.success(withTenant));
  }

  @PostMapping("/login")
  public ResponseEntity<Response<AuthResponse>> login(
      @Valid @RequestBody LoginBody body, HttpServletResponse response) {
    AccessIdentityClient.TokenMaterial material =
        identityClient.login(body.username(), body.password());
    response.addHeader(
        "Set-Cookie", cookieService.bake(material.rawRefreshToken(), refreshTtl()).toString());
    return ResponseEntity.ok(Response.success(material.response()));
  }

  @PostMapping("/refresh")
  public ResponseEntity<Response<AuthResponse>> refresh(
      HttpServletRequest request, HttpServletResponse response) {
    OriginGuard guard = new OriginGuard(properties.getAllowedOrigins());
    guard.assertSameSite(request.getHeader("Origin"), request.getHeader("Referer"));
    String raw = cookieService.read(request);
    if (raw == null) {
      return ResponseEntity.status(401)
          .body(Response.error(ai.cerbur.crag.common.dto.result.ResponseCode.UNAUTHENTICATED));
    }
    AccessIdentityClient.TokenMaterial material = identityClient.refresh(raw);
    response.addHeader(
        "Set-Cookie", cookieService.bake(material.rawRefreshToken(), refreshTtl()).toString());
    return ResponseEntity.ok(Response.success(material.response()));
  }

  @PostMapping("/logout")
  public ResponseEntity<Response<Void>> logout(
      HttpServletRequest request, HttpServletResponse response) {
    OriginGuard guard = new OriginGuard(properties.getAllowedOrigins());
    try {
      guard.assertSameSite(request.getHeader("Origin"), request.getHeader("Referer"));
    } catch (InvalidOriginException e) {
      // 即使 Origin 校验失败也清除本地 Cookie
      response.addHeader("Set-Cookie", cookieService.clear().toString());
      throw e;
    }
    try {
      ConsolePrincipal principal =
          (ConsolePrincipal) request.getAttribute(BearerTokenAuthenticationFilter.PRINCIPAL_ATTR);
      if (principal == null) {
        // logout 不强制 Bearer（依赖 Cookie），但若有 principal 一并撤销 family
        return ResponseEntity.ok(Response.success(null));
      }
      identityClient.logout(principal.userId(), principal.sessionFamilyId());
      return ResponseEntity.ok(Response.success(null));
    } finally {
      // 无论 Access 结果如何都清除 Cookie
      response.addHeader("Set-Cookie", cookieService.clear().toString());
    }
  }

  @GetMapping("/me")
  public ResponseEntity<Response<UserResponse>> me(
      @RequestAttribute(value = BearerTokenAuthenticationFilter.PRINCIPAL_ATTR, required = false)
          ConsolePrincipal principal) {
    if (principal == null) {
      return ResponseEntity.status(401)
          .body(Response.error(ai.cerbur.crag.common.dto.result.ResponseCode.UNAUTHENTICATED));
    }
    return ResponseEntity.ok(Response.success(identityClient.getUserProfile(principal.userId())));
  }

  private Duration refreshTtl() {
    return properties.getCookie().getRefreshTtl();
  }

  private static long parseLong(String userId) {
    try {
      return Long.parseLong(userId.trim());
    } catch (NumberFormatException e) {
      return 0L;
    }
  }

  /** 测试可访问的请求体（包级公开）。 */
  public record RegisterBody(
      @NotBlank(message = "nickname must not be blank")
          @Size(min = 1, max = 64, message = "nickname must be 1-64 chars")
          String nickname,
      @NotBlank(message = "username must not be blank")
          @Size(min = 3, max = 32, message = "username must be 3-32 chars")
          String username,
      @NotBlank(message = "password must not be blank")
          @Size(min = 12, max = 128, message = "password must be 12-128 chars")
          String password) {}

  /** 测试可访问的请求体（包级公开）。 */
  public record LoginBody(
      @NotBlank(message = "username must not be blank") String username,
      @NotBlank(message = "password must not be blank") String password) {}
}
