package ai.cerbur.crag.console.security.filter;

import ai.cerbur.crag.console.security.jwt.AccessJwtKeyRefresher;
import ai.cerbur.crag.console.security.jwt.AccessJwtVerifier;
import ai.cerbur.crag.console.security.jwt.ConsolePrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Console Bearer Token 认证过滤器（plan_21/21.6）。
 *
 * <p>从 {@code Authorization: Bearer <jwt>} 读取 Access JWT，调用本地 verifier 校验后写入 {@code
 * "consolePrincipal"} 请求属性。
 *
 * <p>策略：
 *
 * <ul>
 *   <li>{@code /api/v1/auth/register|login} 为公开端点，不要求 Bearer。
 *   <li>其他 {@code /api/v1/**} 缺失或无效 Bearer 返回 401（不泄漏具体原因）。
 *   <li>公开端点由后续 Controller 自行处理。
 * </ul>
 *
 * <p>公钥从未加载成功时，需要认证的请求返回 503（DOWNSTREAM_UNAVAILABLE），避免在 Access 不可达时静默放行。
 */
@Component
public class BearerTokenAuthenticationFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(BearerTokenAuthenticationFilter.class);
  public static final String PRINCIPAL_ATTR = "consolePrincipal";

  @Autowired private AccessJwtKeyRefresher keyRefresher;

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String path = request.getRequestURI();
    if (isPublic(path)) {
      filterChain.doFilter(request, response);
      return;
    }
    if (!keyRefresher.isInitialLoadOk()) {
      writeError(response, HttpStatus.SERVICE_UNAVAILABLE, 50301, "Downstream unavailable");
      return;
    }
    String auth = request.getHeader("Authorization");
    if (auth == null || !auth.startsWith("Bearer ")) {
      writeError(response, HttpStatus.UNAUTHORIZED, 40101, "Unauthenticated");
      return;
    }
    String token = auth.substring(7).trim();
    AccessJwtVerifier verifier = keyRefresher.verifier();
    ConsolePrincipal principal;
    try {
      principal = verifier.verify(token);
    } catch (RuntimeException e) {
      log.debug("Access JWT 验签失败 — path={} type={}", path, e.getClass().getSimpleName());
      writeError(response, HttpStatus.UNAUTHORIZED, 40101, "Unauthenticated");
      return;
    }
    request.setAttribute(PRINCIPAL_ATTR, principal);
    filterChain.doFilter(request, response);
  }

  private boolean isPublic(String path) {
    return path != null
        && (path.equals("/api/v1/auth/register")
            || path.equals("/api/v1/auth/login")
            || path.equals("/api/v1/auth/refresh")
            || path.equals("/api/v1/auth/logout")
            || path.startsWith("/actuator"));
  }

  private void writeError(HttpServletResponse response, HttpStatus status, int code, String message)
      throws IOException {
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response
        .getWriter()
        .write(
            "{\"success\":false,\"code\":"
                + code
                + ",\"result\":{\"message\":\""
                + message
                + "\",\"retryable\":"
                + (code == 50301)
                + "}}");
  }
}
