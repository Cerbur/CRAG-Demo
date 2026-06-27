package ai.cerbur.crag.access.security;

/**
 * 高熵秘密的 HMAC 契约。实现使用独立 Pepper 的 HMAC-SHA-256，输出十六进制编码串供唯一索引定位与恒定时间比较。
 *
 * <p>用于 Refresh Token 和 API Key 秘密：数据库只存 HMAC，永不存原文。
 */
public interface SecretHmac {

  /** 计算 {@code secret} 的 HMAC-SHA-256，返回十六进制小写编码串。 */
  String digest(String secret);

  /** 恒定时间比较 {@code secret} 的 HMAC 是否匹配 {@code expectedDigestHex}。 */
  boolean matches(String secret, String expectedDigestHex);
}
