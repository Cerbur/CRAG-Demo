package ai.cerbur.crag.open.auth.service;

/**
 * Bearer API Key 提取器（plan_21/21.10）。
 *
 * <p>从 {@code Authorization: Bearer crag_...} 提取完整 Key。缺失/格式错误抛 {@link MissingApiKeyException}。
 */
public final class BearerApiKeyExtractor {

  public static final String PREFIX = "Bearer ";

  private BearerApiKeyExtractor() {}

  /**
   * @param authorization Authorization 头值
   * @return 完整 API Key（去除前缀）
   * @throws MissingApiKeyException 缺失或格式错误
   */
  public static String extract(String authorization) {
    if (authorization == null || !authorization.startsWith(PREFIX)) {
      throw new MissingApiKeyException();
    }
    String key = authorization.substring(PREFIX.length()).trim();
    if (key.isEmpty()) {
      throw new MissingApiKeyException();
    }
    return key;
  }

  /** 缺失/格式错误 Bearer；映射为 40101 UNAUTHENTICATED。 */
  public static class MissingApiKeyException extends RuntimeException {
    public MissingApiKeyException() {
      super("missing bearer api key");
    }
  }
}
