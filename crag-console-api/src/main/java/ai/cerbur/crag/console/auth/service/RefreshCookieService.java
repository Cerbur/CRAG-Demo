package ai.cerbur.crag.console.auth.service;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import org.springframework.http.ResponseCookie;

/**
 * Refresh Token Cookie 管理器（plan_21/21.6）。
 *
 * <p>Refresh Token 只进 HttpOnly Cookie；默认 {@code Secure + SameSite=Lax + Path=/api/v1/auth}，不设置
 * Domain。 本地 HTTP 通过显式配置 {@code crag.console.cookie.secure=false} 关闭 Secure；正式配置不得静默降级。
 *
 * <p>不记录完整 Refresh Token。Bean 由 {@code ConsoleAuthConfiguration} 注册（secure 来自 {@link
 * ai.cerbur.crag.console.config.ConsoleAuthProperties}）。
 */
public class RefreshCookieService {

  static final String COOKIE_NAME = "refresh_token";
  static final String COOKIE_PATH = "/api/v1/auth";
  static final String SAME_SITE = "Lax";

  private final boolean secure;

  /** 测试与构造路径可显式指定 secure. */
  public RefreshCookieService(boolean secure) {
    this.secure = secure;
  }

  /** 烘焙携带 Refresh Token 的 HttpOnly Cookie. */
  public ResponseCookie bake(String refreshToken, Duration ttl) {
    return base(refreshToken, ttl).build();
  }

  /** 清除 Cookie（Max-Age=0，value 空），属性保持一致. */
  public ResponseCookie clear() {
    return base("", Duration.ZERO).maxAge(Duration.ZERO).build();
  }

  /** 从请求的 Cookie 头读取 refresh_token；缺失返回 null. */
  public String read(HttpServletRequest request) {
    Cookie[] cookies = request == null ? null : request.getCookies();
    if (cookies == null) {
      return null;
    }
    for (Cookie c : cookies) {
      if (COOKIE_NAME.equals(c.getName())) {
        return c.getValue();
      }
    }
    return null;
  }

  private ResponseCookie.ResponseCookieBuilder base(String value, Duration ttl) {
    return ResponseCookie.from(COOKIE_NAME, value)
        .httpOnly(true)
        .secure(secure)
        .sameSite(SAME_SITE)
        .path(COOKIE_PATH)
        .maxAge(ttl);
  }
}
