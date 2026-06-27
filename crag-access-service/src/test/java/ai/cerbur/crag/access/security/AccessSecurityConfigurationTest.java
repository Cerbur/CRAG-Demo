package ai.cerbur.crag.access.security;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Access 安全基线轻量组件测试。
 *
 * <p>验证 PasswordHasher（Argon2id）、SecretGenerator、两份独立 Pepper 的 SecretHmac 装配可用，以及 {@link
 * AccessSecretsHealthIndicator} 在秘密齐全时 UP、缺失时 DOWN。
 */
@SpringBootTest
class AccessSecurityConfigurationTest {

  @Autowired private PasswordHasher passwordHasher;
  @Autowired private SecretGenerator secretGenerator;

  @Autowired
  @Qualifier(AccessSecurityConfiguration.REFRESH_TOKEN_HMAC)
  private SecretHmac refreshTokenHmac;

  @Autowired
  @Qualifier(AccessSecurityConfiguration.API_KEY_HMAC)
  private SecretHmac apiKeyHmac;

  @Autowired private AccessSecretsHealthIndicator healthIndicator;
  @Autowired private AccessSecurityProperties properties;

  @Test
  @DisplayName("Argon2id 哈希可校验且不同密码哈希不同")
  void passwordHashRoundTrip() {
    char[] password = "correct-horse-battery-12".toCharArray();
    String hash = passwordHasher.hash(password);
    assertNotEquals("correct-horse-battery-12", hash);
    assertTrue(passwordHasher.hash("correct-horse-battery-12".toCharArray()).length() > 0);
    assertTrue(passwordHasher.matches("correct-horse-battery-12".toCharArray(), hash));
    assertFalse(passwordHasher.matches("wrong-password-12345".toCharArray(), hash));
  }

  @Test
  @DisplayName("密码 char[] 使用后被清零")
  void passwordCharCleared() {
    char[] password = "correct-horse-battery-12".toCharArray();
    passwordHasher.hash(password);
    for (char c : password) {
      assertEquals('\0', c);
    }
  }

  @Test
  @DisplayName("SecretGenerator 产生 Base64URL 无填充且长度对应字节")
  void secretGeneratorBase64Url() {
    String secret = secretGenerator.randomBase64Url(32);
    byte[] decoded = Base64.getUrlDecoder().decode(secret);
    assertEquals(32, decoded.length);
    assertFalse(secret.contains("="));
  }

  @Test
  @DisplayName("HMAC 确定性输出且恒定时间比较正确")
  void hmacDeterministicAndMatches() {
    String digest = refreshTokenHmac.digest("token-secret");
    assertEquals(digest, refreshTokenHmac.digest("token-secret"));
    assertTrue(refreshTokenHmac.matches("token-secret", digest));
    assertFalse(refreshTokenHmac.matches("other-secret", digest));
    // 独立 Pepper：API Key HMAC 与 Refresh Token HMAC 不同
    assertNotEquals(digest, apiKeyHmac.digest("token-secret"));
  }

  @Test
  @DisplayName("秘密齐全时就绪检查 UP")
  void healthUpWhenSecretsPresent() {
    assertEquals(Status.UP, healthIndicator.health().getStatus());
  }

  @Test
  @DisplayName("缺失任一秘密时就绪检查 DOWN")
  void healthDownWhenSecretMissing() {
    AccessSecurityProperties blank = new AccessSecurityProperties();
    AccessSecretsHealthIndicator indicator = new AccessSecretsHealthIndicator(blank);
    assertEquals(Status.DOWN, indicator.health().getStatus());

    AccessSecurityProperties partial = new AccessSecurityProperties();
    partial.getJwt().setPrivateKeyPem("x");
    AccessSecretsHealthIndicator partialIndicator = new AccessSecretsHealthIndicator(partial);
    assertEquals(Status.DOWN, partialIndicator.health().getStatus());
  }

  @Test
  @DisplayName("Argon2 参数不低于基线")
  void argon2BaselineEnforced() {
    AccessSecurityProperties.Argon2 argon2 = properties.getArgon2();
    assertTrue(argon2.getMemoryKiB() >= 65536);
    assertTrue(argon2.getIterations() >= 3);
    assertTrue(argon2.getHashLength() >= 32);
  }
}
