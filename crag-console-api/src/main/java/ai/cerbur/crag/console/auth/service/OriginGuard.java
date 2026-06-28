package ai.cerbur.crag.console.auth.service;

import java.net.URI;
import java.util.Set;

/**
 * Console 同站 Origin/Referer 校验器（plan_21/21.6）。
 *
 * <p>refresh/logout 在处理 Cookie 前必须校验同站 Origin（缺失时回退 Referer）。本类不引入前端可读 CSRF Token；跨站请求统一拒绝。
 * 允许列表为显式配置的 origin（scheme + host + port 三者必须完全匹配）。
 */
public class OriginGuard {

  private final Set<String> allowed;

  public OriginGuard(Set<String> allowedOrigins) {
    this.allowed = allowedOrigins == null ? Set.of() : Set.copyOf(allowedOrigins);
  }

  /**
   * 校验 Origin（缺失时回退 Referer）是否同站。
   *
   * @return true 表示同站
   * @throws InvalidOriginException 缺失或跨站
   */
  public boolean assertSameSite(String origin, String referer) {
    String candidate = origin != null ? origin : referer;
    if (candidate == null || candidate.isBlank()) {
      throw new InvalidOriginException("missing origin and referer");
    }
    String normalized = normalize(candidate);
    if (normalized == null) {
      throw new InvalidOriginException("malformed origin");
    }
    if (!allowed.contains(normalized)) {
      throw new InvalidOriginException("cross-site origin");
    }
    return true;
  }

  private String normalize(String raw) {
    try {
      URI uri = URI.create(raw);
      String scheme = uri.getScheme();
      String host = uri.getHost();
      int port = uri.getPort();
      if (scheme == null || host == null) {
        return null;
      }
      StringBuilder sb = new StringBuilder().append(scheme).append("://").append(host);
      if (port != -1) {
        sb.append(":").append(port);
      }
      return sb.toString();
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
