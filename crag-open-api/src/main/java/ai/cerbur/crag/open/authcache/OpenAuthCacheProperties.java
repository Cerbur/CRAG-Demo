package ai.cerbur.crag.open.authcache;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Open API Key 缓存配置（plan_21/21.10）。
 *
 * <p>默认 TTL 30 秒、最大 10,000 项；两者均可配置。进程重启后缓存为空。
 */
@ConfigurationProperties(prefix = "crag.open.auth-cache")
public class OpenAuthCacheProperties {

  /** 缓存 TTL，默认 30 秒。 */
  private Duration ttl = Duration.ofSeconds(30);

  /** 最大条目数，默认 10,000。 */
  private int capacity = 10_000;

  public Duration getTtl() {
    return ttl;
  }

  public void setTtl(Duration ttl) {
    this.ttl = ttl;
  }

  public int getCapacity() {
    return capacity;
  }

  public void setCapacity(int capacity) {
    this.capacity = capacity;
  }
}
