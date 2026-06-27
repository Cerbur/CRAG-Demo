package ai.cerbur.crag.access.security;

/**
 * 随机秘密生成契约。实现使用 {@link java.security.SecureRandom}，输出 Base64URL 无填充编码。
 *
 * <p>用于 Refresh Token 秘密（32 字节）和 API Key 秘密（32 字节）。
 */
public interface SecretGenerator {

  /** 生成 {@code byteCount} 字节的随机秘密，返回 Base64URL 无填充编码字符串。 */
  String randomBase64Url(int byteCount);

  /** 生成 {@code count} 个字母数字字符（{@code A-Za-z0-9}）的随机串，用于可检索前缀。 */
  String randomAlphanumeric(int count);
}
