package ai.cerbur.crag.query.llm.contract;

/**
 * LLM 请求 —— 封装系统提示词、用户提示词及 source 计数.
 *
 * <p>由 {@code PromptBuilder} 产出，供 LLM 调用层（Task 7.3）消费.
 *
 * @param systemPrompt 系统级提示词（角色设定、约束规则）；非 null 非 blank
 * @param userPrompt 用户消息（问题 + context）；非 null 非 blank
 * @param sourceCount context 中包含的 source 数量；必须为正数
 */
public record LlmRequest(String systemPrompt, String userPrompt, int sourceCount) {

  /**
   * 紧凑构造器 —— 拒绝非法状态.
   *
   * @throws IllegalArgumentException systemPrompt 为 null 或 blank
   * @throws IllegalArgumentException userPrompt 为 null 或 blank
   * @throws IllegalArgumentException sourceCount 小于 1
   */
  public LlmRequest {
    if (systemPrompt == null || systemPrompt.isBlank()) {
      throw new IllegalArgumentException("systemPrompt must not be null or blank");
    }
    if (userPrompt == null || userPrompt.isBlank()) {
      throw new IllegalArgumentException("userPrompt must not be null or blank");
    }
    if (sourceCount < 1) {
      throw new IllegalArgumentException("sourceCount must be positive, got " + sourceCount);
    }
  }
}
