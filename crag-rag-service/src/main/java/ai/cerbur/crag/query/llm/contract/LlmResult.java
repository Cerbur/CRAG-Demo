package ai.cerbur.crag.query.llm.contract;

/**
 * LLM 调用结果 —— 包含生成文本和可选的用量信息.
 *
 * @param answer 生成的回答文本，非 null 非 blank
 * @param usage 用量统计（供应商未提供时为 null）
 */
public record LlmResult(String answer, LlmUsage usage) {

  /**
   * 紧凑构造器 —— 拒绝空白回答.
   *
   * @throws IllegalArgumentException answer 为 null 或 blank
   */
  public LlmResult {
    if (answer == null || answer.isBlank()) {
      throw new IllegalArgumentException("answer must not be null or blank");
    }
  }
}
