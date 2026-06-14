package ai.cerbur.crag.query.llm;

/**
 * LLM ChatClient 统一接口 —— 磨平不同 LLM 提供商（DeepSeek / OpenAI / ...）的差异.
 *
 * 基于 Spring AI 管理连接，一期实现 DeepSeek API（OpenAI 兼容协议）.
 * 提示词模板存放在同目录 prompt/ 下，按模型+场景分目录.
 *
 * @since 2026-06-10
 */
public interface ChatClient {

    /**
     * 向 LLM 发送消息并获取回复（骨架，plan_2 实现）.
     *
     * @param systemPrompt 系统提示词
     * @param userMessage  用户消息
     * @return 空字符串
     */
    String chat(String systemPrompt, String userMessage);
}
