package ai.cerbur.crag.access.security;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * 就绪检查：校验正式运行所需的安全秘密是否齐全。
 *
 * <p>正式 Profile 缺失 JWT 私钥、公钥或任一 Pepper 时报 DOWN，禁止回退到 Demo 默认值。Smoke Profile 由 {@code
 * application-smoke.yml} 提供独立测试秘密，故报 UP。本检查只验证存在性，密钥解析与 JWT 签发由 session 安全适配器负责。
 */
@Component("accessSecrets")
public class AccessSecretsHealthIndicator implements HealthIndicator {

  private final AccessSecurityProperties properties;

  public AccessSecretsHealthIndicator(AccessSecurityProperties properties) {
    this.properties = properties;
  }

  @Override
  public Health health() {
    Map<String, Object> missing = new LinkedHashMap<>();
    AccessSecurityProperties.Jwt jwt = properties.getJwt();
    if (isBlank(jwt.getPrivateKeyPem())) {
      missing.put("jwt.private-key-pem", "missing");
    }
    if (isBlank(jwt.getPublicKeyPem())) {
      missing.put("jwt.public-key-pem", "missing");
    }
    if (isBlank(jwt.getKid())) {
      missing.put("jwt.kid", "missing");
    }
    if (isBlank(jwt.getIssuer())) {
      missing.put("jwt.issuer", "missing");
    }
    if (isBlank(jwt.getAudience())) {
      missing.put("jwt.audience", "missing");
    }
    if (isBlank(properties.getPepper().getRefreshToken())) {
      missing.put("pepper.refresh-token", "missing");
    }
    if (isBlank(properties.getPepper().getApiKey())) {
      missing.put("pepper.api-key", "missing");
    }
    if (missing.isEmpty()) {
      return Health.up().withDetail("accessSecrets", "present").build();
    }
    return Health.down().withDetail("accessSecrets", missing).build();
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
