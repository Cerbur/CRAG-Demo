package ai.cerbur.crag.console.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;

/**
 * RefreshCookieService 纯单元测试（plan_21/21.6）。
 *
 * <p>断言：Refresh Token 只进 HttpOnly Cookie；默认 Secure + SameSite=Lax + Path=/api/v1/auth；本地 dev
 * profile 关闭 Secure。
 */
@DisplayName("RefreshCookieService HttpOnly Cookie 属性")
class RefreshCookieServiceTest {

  @Test
  @DisplayName("secure 模式：HttpOnly + Secure + SameSite=Lax + Path=/api/v1/auth + Max-Age")
  void buildsSecureCookie() {
    RefreshCookieService svc = new RefreshCookieService(true);
    ResponseCookie cookie = svc.bake("the-refresh-token", Duration.ofSeconds(3600));

    assertThat(cookie.getName()).isEqualTo("refresh_token");
    assertThat(cookie.getValue()).isEqualTo("the-refresh-token");
    assertThat(cookie.isHttpOnly()).isTrue();
    assertThat(cookie.isSecure()).isTrue();
    assertThat(cookie.getSameSite()).isEqualTo("Lax");
    assertThat(cookie.getPath()).isEqualTo("/api/v1/auth");
    assertThat(cookie.getMaxAge().getSeconds()).isEqualTo(3600L);
    assertThat(cookie.getDomain()).isNull();
  }

  @Test
  @DisplayName("clear：Max-Age=0、value 空、属性保持")
  void clearsCookie() {
    RefreshCookieService svc = new RefreshCookieService(true);
    ResponseCookie cookie = svc.clear();
    assertThat(cookie.getMaxAge().getSeconds()).isZero();
    assertThat(cookie.getValue()).isEmpty();
    assertThat(cookie.isHttpOnly()).isTrue();
    assertThat(cookie.isSecure()).isTrue();
    assertThat(cookie.getPath()).isEqualTo("/api/v1/auth");
  }

  @Test
  @DisplayName("dev 模式：Secure 关闭（显式配置，不得静默降级）")
  void devModeDisablesSecure() {
    RefreshCookieService svc = new RefreshCookieService(false);
    ResponseCookie cookie = svc.bake("rt", Duration.ofSeconds(60));
    assertThat(cookie.isSecure()).isFalse();
  }

  @Test
  @DisplayName("parse：从请求 Cookie 读取 refresh_token；缺失返回 null")
  void parsesCookieValue() {
    RefreshCookieService svc = new RefreshCookieService(true);
    org.springframework.mock.web.MockHttpServletRequest req =
        new org.springframework.mock.web.MockHttpServletRequest();
    req.setCookies(
        new jakarta.servlet.http.Cookie("refresh_token", "abc"),
        new jakarta.servlet.http.Cookie("other", "xyz"));
    assertThat(svc.read(req)).isEqualTo("abc");

    org.springframework.mock.web.MockHttpServletRequest empty =
        new org.springframework.mock.web.MockHttpServletRequest();
    assertThat(svc.read(empty)).isNull();
    assertThat(svc.read(null)).isNull();
  }
}
