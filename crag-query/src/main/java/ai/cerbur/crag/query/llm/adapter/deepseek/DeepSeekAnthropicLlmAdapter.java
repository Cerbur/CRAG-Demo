package ai.cerbur.crag.query.llm.adapter.deepseek;

import ai.cerbur.crag.query.llm.config.QueryProperties;
import ai.cerbur.crag.query.llm.contract.LlmClient;
import ai.cerbur.crag.query.llm.contract.LlmFailureCategory;
import ai.cerbur.crag.query.llm.contract.LlmProviderException;
import ai.cerbur.crag.query.llm.contract.LlmRequest;
import ai.cerbur.crag.query.llm.contract.LlmResult;
import ai.cerbur.crag.query.llm.contract.LlmUsage;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.InternalServerException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * DeepSeek Anthropic 兼容协议适配器 —— 通过 Spring AI 2.0.0 AnthropicChatModel 调用 DeepSeek V4 Flash API.
 *
 * <p>使用 Spring AI 的 AnthropicChatModel（非启动器）手动构造，通过 {@link AnthropicChatOptions} 配置传输与请求参数。本适配器负责将
 * {@link LlmRequest} 映射为 Spring AI 的 {@link Prompt}，调用模型并对响应进行 分类与错误处理。
 *
 * <p>Thinking 块由 Spring AI 的 buildGenerations() 解析为独立的 Generation，本适配器仅取最后一个 Generation（即 text +
 * tool calls 消息）进行处理。Tool calls 视为协议错误。
 */
public class DeepSeekAnthropicLlmAdapter implements LlmClient {

  private final AnthropicChatModel chatModel;
  private final QueryProperties properties;

  /**
   * @param chatModel 已配置好的 AnthropicChatModel（含 apiKey、baseUrl、timeout、maxRetries=0）
   * @param properties Query 模块配置，用于读取 DeepSeek 提供商参数（model、temperature、maxOutputTokens）
   */
  public DeepSeekAnthropicLlmAdapter(AnthropicChatModel chatModel, QueryProperties properties) {
    this.chatModel = chatModel;
    this.properties = properties;
  }

  @Override
  public LlmResult generate(LlmRequest request) throws LlmProviderException {
    try {
      var systemMessage = new SystemMessage(request.systemPrompt());
      var userMessage = new UserMessage(request.userPrompt());
      var deepseek = properties.getLlm().deepseek();

      var options =
          AnthropicChatOptions.builder()
              .model(deepseek.model())
              .temperature(deepseek.temperature())
              .maxTokens(deepseek.maxOutputTokens())
              .build();

      var prompt = new Prompt(List.of(systemMessage, userMessage), options);
      var response = chatModel.call(prompt);

      return parseResponse(response);
    } catch (LlmProviderException e) {
      throw e;
    } catch (Exception e) {
      throw mapError(e);
    }
  }

  /**
   * 解析 Spring AI ChatResponse 为 {@link LlmResult}.
   *
   * <p>规则：
   *
   * <ul>
   *   <li>thinking 块为独立 Generation，取最后一个 Generation 作为文本+工具调用消息
   *   <li>工具调用 → PROTOCOL
   *   <li>空白文本 → EMPTY_RESPONSE
   *   <li>停止原因为 max_tokens / tool_use → TRUNCATED_RESPONSE
   * </ul>
   */
  private LlmResult parseResponse(ChatResponse response) {
    var results = response.getResults();
    if (results == null || results.isEmpty()) {
      throw new LlmProviderException(
          LlmFailureCategory.EMPTY_RESPONSE, "No generations in response", null);
    }

    // 最后一个 Generation 始终是 text + tool calls 消息（前置 Generation 为 thinking / redacted-thinking
    // 块）
    Generation textGeneration = results.get(results.size() - 1);
    var assistantMessage = textGeneration.getOutput();

    // 工具调用 → 协议错误
    var toolCalls = assistantMessage.getToolCalls();
    if (toolCalls != null && !toolCalls.isEmpty()) {
      throw new LlmProviderException(
          LlmFailureCategory.PROTOCOL, "Unexpected tool calls in response", null);
    }

    // 提取文本
    var text = assistantMessage.getText();
    if (text == null || text.isBlank()) {
      throw new LlmProviderException(
          LlmFailureCategory.EMPTY_RESPONSE, "Empty response text", null);
    }

    // 检查停止原因
    var metadata = textGeneration.getMetadata();
    if (metadata != null) {
      var finishReason = metadata.getFinishReason();
      if ("max_tokens".equals(finishReason) || "tool_use".equals(finishReason)) {
        throw new LlmProviderException(
            LlmFailureCategory.TRUNCATED_RESPONSE, "Response truncated: " + finishReason, null);
      }
    }

    // 提取用量
    LlmUsage usage = extractUsage(response);

    return new LlmResult(text.trim(), usage);
  }

  /** 从响应中提取用量信息，包括 thinking tokens（通过 native usage 获取）. */
  private LlmUsage extractUsage(ChatResponse response) {
    var chatMetadata = response.getMetadata();
    if (chatMetadata == null) {
      return null;
    }
    var usageInfo = chatMetadata.getUsage();
    if (usageInfo == null) {
      return null;
    }

    Integer inputTokens = usageInfo.getPromptTokens();
    Integer outputTokens = usageInfo.getCompletionTokens();
    Integer thinkingTokens = null;

    // 从 native usage 提取 thinking tokens
    Object nativeUsage = usageInfo.getNativeUsage();
    if (nativeUsage instanceof com.anthropic.models.messages.Usage anthropicUsage) {
      thinkingTokens =
          anthropicUsage
              .outputTokensDetails()
              .map(details -> Math.toIntExact(details.thinkingTokens()))
              .orElse(null);
    }

    return new LlmUsage(inputTokens, outputTokens, thinkingTokens);
  }

  /** 将 Spring AI / Anthropic SDK 异常映射为 {@link LlmProviderException}. */
  private static LlmProviderException mapError(Exception e) {
    // 401
    if (e instanceof UnauthorizedException) {
      return new LlmProviderException(LlmFailureCategory.AUTHENTICATION, e.getMessage(), e);
    }
    // 429
    if (e instanceof RateLimitException) {
      return new LlmProviderException(LlmFailureCategory.RATE_LIMITED, e.getMessage(), e);
    }
    // 5xx
    if (e instanceof InternalServerException) {
      return new LlmProviderException(LlmFailureCategory.SERVER_ERROR, e.getMessage(), e);
    }
    // 其他 HTTP 错误
    if (e instanceof AnthropicServiceException) {
      return new LlmProviderException(LlmFailureCategory.UNKNOWN, e.getMessage(), e);
    }

    // 检查原因链中的超时
    Throwable cause = e;
    while (cause != null) {
      if (cause instanceof SocketTimeoutException || cause instanceof TimeoutException) {
        return new LlmProviderException(LlmFailureCategory.TIMEOUT, e.getMessage(), e);
      }
      cause = cause.getCause();
    }

    // AnthropicIoException（连接 / IO 错误）
    if (e instanceof AnthropicIoException) {
      return new LlmProviderException(LlmFailureCategory.UNKNOWN, e.getMessage(), e);
    }

    return new LlmProviderException(LlmFailureCategory.UNKNOWN, e.getMessage(), e);
  }
}
