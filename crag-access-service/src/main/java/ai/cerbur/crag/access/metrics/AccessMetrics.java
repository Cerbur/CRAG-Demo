package ai.cerbur.crag.access.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Access 可观测计数器：认证、Refresh 复用、权限拒绝、API Key 鉴权与失效事件发布。
 *
 * <p>计数器按需注册，业务调用方在关键路径上递增；不记录密码、Token 或完整 Key。
 */
@Component
public class AccessMetrics {

  @Autowired private MeterRegistry registry;

  /** 记录认证（注册/登录/刷新）成功或失败。 */
  public void authentication(String channel, boolean success) {
    registry
        .counter("access.authentication", "channel", channel, "result", result(success))
        .increment();
  }

  /** 记录 Refresh Token 复用检测命中。 */
  public void refreshReuseDetected() {
    registry.counter("access.refresh.reuse").increment();
  }

  /** 记录 Membership 权限拒绝。 */
  public void membershipDenied() {
    registry.counter("access.membership.denied").increment();
  }

  /** 记录 API Key 鉴权成功或失败。 */
  public void apiKeyAuthentication(boolean success) {
    registry.counter("access.apikey.authentication", "result", result(success)).increment();
  }

  /** 记录 API Key 失效事件写入 Outbox。 */
  public void apiKeyInvalidationPublished() {
    registry.counter("access.apikey.invalidation.published").increment();
  }

  private static String result(boolean success) {
    return success ? "success" : "failure";
  }
}
