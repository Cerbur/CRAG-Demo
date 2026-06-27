package ai.cerbur.crag.access.core.apikey;

import java.time.Duration;

/**
 * API Key 输入规则与格式解析。
 *
 * <p>完整 Key 格式 {@code crag_<12 字母数字前缀>_<32 字节秘密 Base64URL>}。前缀仅字母数字，保证按 {@code _} 拆分无歧义。TTL 默认 90
 * 天， 上限 365 天，禁止永不过期；名称长度 1–64。
 */
public final class ApiKeyPolicy {

  public static final String KEY_PREFIX = "crag_";
  public static final int PREFIX_LENGTH = 12;
  public static final int SECRET_BYTES = 32;
  public static final Duration DEFAULT_TTL = Duration.ofDays(90);
  public static final Duration MAX_TTL = Duration.ofDays(365);

  private ApiKeyPolicy() {}

  /** 校验名称长度 1–64。 */
  public static String validateName(String name) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("api key name must not be blank");
    }
    String trimmed = name.trim();
    if (trimmed.length() < 1 || trimmed.length() > 64) {
      throw new IllegalArgumentException("api key name length must be 1-64");
    }
    return trimmed;
  }

  /** 解析 TTL：null 用默认 90 天；超过 365 天抛非法参数；禁止永不过期。 */
  public static Duration resolveTtl(Duration ttl) {
    Duration resolved = ttl == null ? DEFAULT_TTL : ttl;
    if (resolved.isZero() || resolved.isNegative()) {
      throw new IllegalArgumentException("api key ttl must be positive");
    }
    if (resolved.compareTo(MAX_TTL) > 0) {
      throw new IllegalArgumentException("api key ttl must not exceed 365 days");
    }
    return resolved;
  }

  /** 解析完整 Key，返回前缀与秘密；格式非法抛非法参数。 */
  public static ParsedKey parseCompleteKey(String completeKey) {
    if (completeKey == null || !completeKey.startsWith(KEY_PREFIX)) {
      throw new IllegalArgumentException("invalid api key format");
    }
    String rest = completeKey.substring(KEY_PREFIX.length());
    int separator = rest.indexOf('_');
    if (separator != PREFIX_LENGTH) {
      throw new IllegalArgumentException("invalid api key format");
    }
    String prefix = rest.substring(0, separator);
    String secret = rest.substring(separator + 1);
    if (prefix.length() != PREFIX_LENGTH || secret.isEmpty()) {
      throw new IllegalArgumentException("invalid api key format");
    }
    for (int i = 0; i < prefix.length(); i++) {
      if (!Character.isLetterOrDigit(prefix.charAt(i))) {
        throw new IllegalArgumentException("invalid api key format");
      }
    }
    return new ParsedKey(prefix, secret);
  }

  /** 拼接完整 Key。 */
  public static String buildCompleteKey(String prefix, String secret) {
    return KEY_PREFIX + prefix + "_" + secret;
  }

  /** 解析出的前缀与秘密。 */
  public record ParsedKey(String prefix, String secret) {}
}
