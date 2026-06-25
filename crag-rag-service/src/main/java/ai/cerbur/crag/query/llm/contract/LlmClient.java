package ai.cerbur.crag.query.llm.contract;

/**
 * 供应商无关的 LLM 客户端接口 —— 定义 LLM 调用的统一契约.
 *
 * <p>实现类负责与具体提供商（DeepSeek、OpenAI 等）通信，或作为测试替身返回固定结果.
 */
public interface LlmClient {

  /**
   * 向 LLM 发送请求并获取生成结果.
   *
   * @param request LLM 请求（包含 systemPrompt、userPrompt 和 sourceCount）
   * @return 生成结果
   * @throws LlmProviderException LLM 调用失败时抛出
   * @throws IllegalArgumentException 请求参数非法时抛出
   */
  LlmResult generate(LlmRequest request) throws LlmProviderException;
}
