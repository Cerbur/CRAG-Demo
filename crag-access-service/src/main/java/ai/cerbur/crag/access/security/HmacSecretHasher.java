package ai.cerbur.crag.access.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 独立 Pepper 的 HMAC-SHA-256 实现。
 *
 * <p>对 {@code secret} 计算 HMAC-SHA-256，返回十六进制小写编码串，供数据库唯一索引定位与恒定时间比较。 Pepper 由构造注入；正式 Profile 缺失
 * Pepper 时由就绪检查报 DOWN，本实现仍可构造（空 Pepper 仍可计算，但不安全）。
 */
public class HmacSecretHasher implements SecretHmac {

  private static final String ALGORITHM = "HmacSHA256";

  private final byte[] pepperBytes;

  public HmacSecretHasher(String pepper) {
    this.pepperBytes = pepper.getBytes(StandardCharsets.UTF_8);
  }

  @Override
  public String digest(String secret) {
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(new SecretKeySpec(pepperBytes, ALGORITHM));
      byte[] hash = mac.doFinal(secret.getBytes(StandardCharsets.UTF_8));
      return toHex(hash);
    } catch (Exception e) {
      throw new IllegalStateException("HMAC computation failed", e);
    }
  }

  @Override
  public boolean matches(String secret, String expectedDigestHex) {
    String actual = digest(secret);
    return MessageDigest.isEqual(
        actual.getBytes(StandardCharsets.UTF_8),
        expectedDigestHex.getBytes(StandardCharsets.UTF_8));
  }

  private static String toHex(byte[] bytes) {
    StringBuilder sb = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      sb.append(String.format("%02x", b & 0xff));
    }
    return sb.toString();
  }
}
