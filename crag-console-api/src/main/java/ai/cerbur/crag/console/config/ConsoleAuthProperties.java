package ai.cerbur.crag.console.config;

import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Console 认证与 Cookie 配置（plan_21/21.6）。
 *
 * <p>{@code crag.console.allowed-origins} 为允许的 Origin（scheme://host[:port]），refresh/logout 同站校验使用。
 * {@code crag.console.cookie.secure=false} 仅用于本地 HTTP；正式配置不得静默降级（默认 true）。
 */
@Configuration
@ConfigurationProperties(prefix = "crag.console")
public class ConsoleAuthProperties {

  private Set<String> allowedOrigins = Set.of();
  private Cookie cookie = new Cookie();

  public Set<String> getAllowedOrigins() {
    return allowedOrigins;
  }

  public void setAllowedOrigins(Set<String> allowedOrigins) {
    this.allowedOrigins = allowedOrigins;
  }

  public Cookie getCookie() {
    return cookie;
  }

  public void setCookie(Cookie cookie) {
    this.cookie = cookie;
  }

  /** Cookie 配置。 */
  public static class Cookie {
    private boolean secure = true;
    private Duration refreshTtl = Duration.ofDays(7);

    public boolean isSecure() {
      return secure;
    }

    public void setSecure(boolean secure) {
      this.secure = secure;
    }

    public Duration getRefreshTtl() {
      return refreshTtl;
    }

    public void setRefreshTtl(Duration refreshTtl) {
      this.refreshTtl = refreshTtl;
    }
  }

  /** 方便测试与 Controller 复用。 */
  public static class ConsoleAuthCookieProperties extends Cookie {}
}
