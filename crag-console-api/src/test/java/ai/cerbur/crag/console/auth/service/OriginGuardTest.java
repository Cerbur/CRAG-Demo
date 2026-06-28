package ai.cerbur.crag.console.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * OriginGuard 纯单元测试（plan_21/21.6）。
 *
 * <p>断言：refresh/logout 校验同站 Origin/Referer；缺失或跨站拒绝；不引入前端可读 CSRF Token。
 */
@DisplayName("OriginGuard 同站校验")
class OriginGuardTest {

  private final OriginGuard guard =
      new OriginGuard(Set.of("https://console.example.com", "http://localhost:8080"));

  @Test
  @DisplayName("匹配允许的 Origin → 通过")
  void allowsKnownOrigin() {
    assertThat(guard.assertSameSite("https://console.example.com", null)).isTrue();
  }

  @Test
  @DisplayName("缺失 Origin/Referer → 拒绝")
  void rejectsMissing() {
    assertThatThrownBy(() -> guard.assertSameSite(null, null))
        .isInstanceOf(InvalidOriginException.class);
  }

  @Test
  @DisplayName("跨站 Origin → 拒绝")
  void rejectsCrossSite() {
    assertThatThrownBy(() -> guard.assertSameSite("https://evil.example.com", null))
        .isInstanceOf(InvalidOriginException.class);
  }

  @Test
  @DisplayName("缺失 Origin 时回退 Referer 同站")
  void fallsBackToReferer() {
    assertThat(guard.assertSameSite(null, "https://console.example.com/api/v1/auth/refresh"))
        .isTrue();
  }

  @Test
  @DisplayName("Origin 与允许站点同 host 不同 scheme 端口 → 视为跨站")
  void rejectsPortMismatch() {
    assertThatThrownBy(() -> guard.assertSameSite("https://console.example.com:9000", null))
        .isInstanceOf(InvalidOriginException.class);
  }
}
