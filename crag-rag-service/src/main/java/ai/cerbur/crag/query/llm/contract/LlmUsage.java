package ai.cerbur.crag.query.llm.contract;

/**
 * LLM 调用用量 —— 供应商中立的 token 计数.
 *
 * <p>所有字段均为 {@link Integer} 而非原始 {@code int}，当供应商未提供某计数时值为 {@code null}， 不得伪造为 0. 供应商全部不提供时返回
 * {@code null}（如 Stub 模式）.
 *
 * @param inputTokens 输入 token 数（供应商未提供时为 null）
 * @param outputTokens 输出 token 数（供应商未提供时为 null）
 * @param thinkingTokens 思考 / reasoning token 数（供应商未提供时为 null；DeepSeek 特有）
 */
public record LlmUsage(Integer inputTokens, Integer outputTokens, Integer thinkingTokens) {}
