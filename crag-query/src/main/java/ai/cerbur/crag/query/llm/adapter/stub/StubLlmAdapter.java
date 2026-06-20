package ai.cerbur.crag.query.llm.adapter.stub;

import ai.cerbur.crag.query.llm.config.QueryProperties;
import ai.cerbur.crag.query.llm.config.QueryProperties.StubMode;
import ai.cerbur.crag.query.llm.contract.LlmClient;
import ai.cerbur.crag.query.llm.contract.LlmFailureCategory;
import ai.cerbur.crag.query.llm.contract.LlmProviderException;
import ai.cerbur.crag.query.llm.contract.LlmRequest;
import ai.cerbur.crag.query.llm.contract.LlmResult;

/**
 * 确定性 Stub LLM 适配器 —— 测试替身，不连接任何真实提供商.
 *
 * <p>两种模式：
 *
 * <ul>
 *   <li>{@code SUCCESS}：当 {@code request.sourceCount() > 0} 时返回固定中文回答，usage 为 null； 当 {@code
 *       sourceCount <= 0} 时抛出 {@link IllegalArgumentException}。
 *   <li>{@code FAILURE}：抛出 {@link LlmProviderException}，分类为 {@link LlmFailureCategory#UNKNOWN}。
 * </ul>
 *
 * <p>本适配器不解析问题、提示词或 evidence 内容，也不调用任何网络。
 */
public class StubLlmAdapter implements LlmClient {

  private static final String FIXED_ANSWER = "已根据知识库证据生成回答。[S1]";

  private final StubMode mode;

  public StubLlmAdapter(QueryProperties properties) {
    QueryProperties.Stub stub = properties.getLlm().stub();
    this.mode = (stub != null && stub.mode() != null) ? stub.mode() : StubMode.SUCCESS;
  }

  @Override
  public LlmResult generate(LlmRequest request) throws LlmProviderException {
    if (mode == StubMode.FAILURE) {
      throw new LlmProviderException(LlmFailureCategory.UNKNOWN, "Stub failure mode", null);
    }

    // SUCCESS mode
    if (request.sourceCount() <= 0) {
      throw new IllegalArgumentException(
          "sourceCount must be positive, got " + request.sourceCount());
    }

    return new LlmResult(FIXED_ANSWER, null);
  }
}
