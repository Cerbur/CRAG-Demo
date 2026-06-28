package ai.cerbur.crag.console.config;

import ai.cerbur.crag.console.auth.service.RefreshCookieService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Console Auth Bean 装配（plan_21/21.6）。
 *
 * <p>注册 {@link RefreshCookieService}，其 {@code secure} 标志绑定到 {@link ConsoleAuthProperties}；正式配置默认
 * true，本地 HTTP 显式关闭。
 */
@Configuration
public class ConsoleAuthConfiguration {

  @Bean
  public RefreshCookieService refreshCookieService(ConsoleAuthProperties properties) {
    return new RefreshCookieService(properties.getCookie().isSecure());
  }
}
