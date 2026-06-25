package ai.cerbur.crag.query.llm.contract;

/**
 * LLM 提供商调用异常 —— 封装失败分类和可选的提供商原始消息.
 *
 * <p>异常消息仅包含分类名，不嵌入 {{@code providerMessage}} 以防止 API 密钥意外泄露. 提供商原始消息可通过 {@link
 * #getProviderMessage()} 获取.
 */
public class LlmProviderException extends RuntimeException {

  private final LlmFailureCategory category;
  private final String providerMessage;

  /**
   * 构造 LLM 提供商异常.
   *
   * @param category 失败分类，非 null
   * @param providerMessage 来自底层 SDK/提供商的原始消息，可为 null
   * @param cause 根因异常，可为 null
   */
  public LlmProviderException(
      LlmFailureCategory category, String providerMessage, Throwable cause) {
    super("LLM provider failure: " + category, cause);
    this.category = category;
    this.providerMessage = providerMessage;
  }

  /** 返回失败分类. */
  public LlmFailureCategory getCategory() {
    return category;
  }

  /** 返回来自底层 SDK/提供商的原始消息，可能为 null. */
  public String getProviderMessage() {
    return providerMessage;
  }
}
