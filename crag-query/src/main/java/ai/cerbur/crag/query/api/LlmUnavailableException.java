package ai.cerbur.crag.query.api;

import ai.cerbur.crag.query.llm.contract.LlmProviderException;

/**
 * LLM 不可用异常 —— 表示 LLM 提供商调用失败.
 *
 * <p>由 {@link UserQueryService} 在 LLM 调用失败时抛出，其中 {@code provider} 字段标识使用的提供商（"stub" 或 "deepseek"）.
 * 当由 {@link LlmProviderException} 驱动时，从 {@link LlmProviderException#getCategory()} 提取提供商 标识.
 */
public class LlmUnavailableException extends RuntimeException {

  private final String provider;

  /**
   * 构造 LLM 不可用异常.
   *
   * @param message 异常描述
   * @param cause 根因（通常为 {@link LlmProviderException}）
   */
  public LlmUnavailableException(String message, Throwable cause) {
    super(message, cause);
    this.provider = extractProvider(cause);
  }

  /**
   * 构造 LLM 不可用异常（无根因）.
   *
   * @param message 异常描述
   */
  public LlmUnavailableException(String message) {
    super(message);
    this.provider = "unknown";
  }

  /**
   * 构造 LLM 不可用异常（指定提供商）.
   *
   * @param message 异常描述
   * @param cause 根因
   * @param provider 提供商标识，如 "stub" 或 "deepseek"
   */
  public LlmUnavailableException(String message, Throwable cause, String provider) {
    super(message, cause);
    this.provider = provider;
  }

  /**
   * 返回提供商标识.
   *
   * @return "stub", "deepseek" 或 "unknown"
   */
  public String getProvider() {
    return provider;
  }

  /**
   * 从根因中提取提供商标识.
   *
   * @param cause 根因异常
   * @return 提供商的小写名称，无法识别时返回 "unknown"
   */
  private static String extractProvider(Throwable cause) {
    if (cause instanceof LlmProviderException) {
      // Use the category name as a proxy for provider identity
      String category = ((LlmProviderException) cause).getCategory().name().toLowerCase();
      return "provider_" + category;
    }
    return "unknown";
  }
}
