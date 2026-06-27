package ai.cerbur.crag.access.security;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * 基于 {@link SecureRandom} 的随机秘密生成实现，输出 Base64URL 无填充编码。
 *
 * <p>用于 Refresh Token 与 API Key 高熵秘密。
 */
public class SecureRandomSecretGenerator implements SecretGenerator {

  private static final char[] ALPHANUMERIC =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();

  private final SecureRandom random = new SecureRandom();

  @Override
  public String randomBase64Url(int byteCount) {
    byte[] bytes = new byte[byteCount];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  @Override
  public String randomAlphanumeric(int count) {
    char[] chars = new char[count];
    for (int i = 0; i < count; i++) {
      chars[i] = ALPHANUMERIC[random.nextInt(ALPHANUMERIC.length)];
    }
    return new String(chars);
  }
}
