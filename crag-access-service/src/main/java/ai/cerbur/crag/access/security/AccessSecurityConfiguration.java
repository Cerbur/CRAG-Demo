package ai.cerbur.crag.access.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Access 安全适配器装配。
 *
 * <p>注册 {@link PasswordHasher}（Argon2id）、{@link SecretGenerator}（SecureRandom）和两份独立 Pepper 的 {@link
 * SecretHmac}（Refresh Token 与 API Key）。Argon2 参数不得低于基线。缺失秘密时应用仍可启动，但 {@link
 * AccessSecretsHealthIndicator} 在就绪检查中报 DOWN。
 */
@Configuration
@EnableConfigurationProperties(AccessSecurityProperties.class)
public class AccessSecurityConfiguration {

  /** Bean 名称：Refresh Token 专用 HMAC。注入时使用 {@code @Qualifier("refreshTokenHmac")}。 */
  public static final String REFRESH_TOKEN_HMAC = "refreshTokenHmac";

  /** Bean 名称：API Key 专用 HMAC。注入时使用 {@code @Qualifier("apiKeyHmac")}。 */
  public static final String API_KEY_HMAC = "apiKeyHmac";

  @Bean
  PasswordHasher passwordHasher(AccessSecurityProperties properties) {
    AccessSecurityProperties.Argon2 argon2 = properties.getArgon2();
    assertBaseline(argon2);
    return new Argon2PasswordHasher(
        argon2.getSaltLength(),
        argon2.getHashLength(),
        argon2.getParallelism(),
        argon2.getMemoryKiB(),
        argon2.getIterations());
  }

  @Bean
  SecretGenerator secretGenerator() {
    return new SecureRandomSecretGenerator();
  }

  @Bean(REFRESH_TOKEN_HMAC)
  SecretHmac refreshTokenHmac(AccessSecurityProperties properties) {
    return new HmacSecretHasher(properties.getPepper().getRefreshToken());
  }

  @Bean(API_KEY_HMAC)
  SecretHmac apiKeyHmac(AccessSecurityProperties properties) {
    return new HmacSecretHasher(properties.getPepper().getApiKey());
  }

  /** Argon2 参数不得低于安全基线；低于时启动失败，避免误配弱哈希。 */
  private static void assertBaseline(AccessSecurityProperties.Argon2 argon2) {
    if (argon2.getMemoryKiB() < 65536
        || argon2.getIterations() < 3
        || argon2.getParallelism() < 1
        || argon2.getSaltLength() < 16
        || argon2.getHashLength() < 32) {
      throw new IllegalStateException(
          "Argon2 parameters must not be lower than baseline "
              + "(memoryKiB=65536, iterations=3, parallelism=1, saltLength=16, hashLength=32)");
    }
  }
}
