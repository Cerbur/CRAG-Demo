package ai.cerbur.crag.query.llm.adapter.deepseek;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import ai.cerbur.crag.query.llm.config.DeepSeekApiKey;
import ai.cerbur.crag.query.llm.config.QueryProperties;
import ai.cerbur.crag.query.llm.config.QueryProperties.DeepSeek;
import ai.cerbur.crag.query.llm.config.QueryProperties.Llm;
import ai.cerbur.crag.query.llm.config.QueryProperties.Provider;
import ai.cerbur.crag.query.llm.contract.LlmClient;
import ai.cerbur.crag.query.llm.contract.LlmFailureCategory;
import ai.cerbur.crag.query.llm.contract.LlmProviderException;
import ai.cerbur.crag.query.llm.contract.LlmRequest;
import ai.cerbur.crag.query.llm.contract.LlmResult;
import ai.cerbur.crag.query.llm.contract.LlmUsage;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/** {@link DeepSeekAnthropicLlmAdapter} 的纯单元测试 —— 使用 Mock Spring AI ChatModel. */
@ExtendWith(MockitoExtension.class)
class DeepSeekAnthropicLlmAdapterTest {

  @Mock private AnthropicChatModel chatModel;

  private LlmClient adapter;
  private LlmRequest validRequest;

  @BeforeEach
  void setUp() {
    var deepseek =
        new DeepSeek(
            new DeepSeekApiKey("test-key"),
            "https://api.deepseek.com/anthropic",
            "deepseek-v4-flash",
            0.0,
            4096);
    var properties = new QueryProperties();
    properties.setLlm(new Llm(Provider.DEEPSEEK, Duration.ofSeconds(120), deepseek, null));
    adapter = new DeepSeekAnthropicLlmAdapter(chatModel, properties);
    validRequest =
        new LlmRequest("You are a helpful assistant.", "What is the capital of France?", 3);
  }

  private ChatResponse createResponse(String text, String finishReason) {
    var assistantMessage = new AssistantMessage(text);
    var metadata = ChatGenerationMetadata.builder().finishReason(finishReason).build();
    var generation = new Generation(assistantMessage, metadata);
    var usage = new DefaultUsage(10, 5, 15, null, null, null);
    var responseMeta = ChatResponseMetadata.builder().usage(usage).build();
    return new ChatResponse(List.of(generation), responseMeta);
  }

  private ChatResponse createThinkingResponse(
      String thinkingText, String visibleText, String finishReason) {
    var thinkingMessage =
        AssistantMessage.builder()
            .content(thinkingText)
            .properties(Map.of("signature", "some-signature"))
            .build();
    var thinkingGen = new Generation(thinkingMessage);

    var textMessage = new AssistantMessage(visibleText);
    var metadata = ChatGenerationMetadata.builder().finishReason(finishReason).build();
    var textGen = new Generation(textMessage, metadata);

    var usage = new DefaultUsage(15, 30, 45, null, null, null);
    var responseMeta = ChatResponseMetadata.builder().usage(usage).build();
    return new ChatResponse(List.of(thinkingGen, textGen), responseMeta);
  }

  @Nested
  @DisplayName("正常路径")
  class SuccessPath {

    @Test
    @DisplayName("成功响应返回文本和用法")
    void successfulResponse() {
      when(chatModel.call(any(Prompt.class)))
          .thenReturn(createResponse("Paris is the capital of France.", "end_turn"));

      LlmResult result = adapter.generate(validRequest);

      assertEquals("Paris is the capital of France.", result.answer());
      assertNotNull(result.usage());
      assertEquals(10, result.usage().inputTokens());
      assertEquals(5, result.usage().outputTokens());
    }

    @Test
    @DisplayName("thinking 块被忽略，仅提取可见文本")
    void thinkingBlocksIgnored() {
      when(chatModel.call(any(Prompt.class)))
          .thenReturn(
              createThinkingResponse(
                  "The user asks about France's capital...",
                  "The capital of France is Paris.",
                  "end_turn"));

      LlmResult result = adapter.generate(validRequest);

      assertEquals("The capital of France is Paris.", result.answer());
      assertNotNull(result.usage());
      assertEquals(15, result.usage().inputTokens());
      assertEquals(30, result.usage().outputTokens());
    }

    @Test
    @DisplayName("usage 为 null 时仍返回结果")
    void nullUsage() {
      var assistantMessage = new AssistantMessage("Paris.");
      var metadata = ChatGenerationMetadata.builder().finishReason("end_turn").build();
      var generation = new Generation(assistantMessage, metadata);
      var responseMeta = ChatResponseMetadata.builder().build(); // no usage
      when(chatModel.call(any(Prompt.class)))
          .thenReturn(new ChatResponse(List.of(generation), responseMeta));

      LlmResult result = adapter.generate(validRequest);

      assertEquals("Paris.", result.answer());
      LlmUsage expectedUsage = new LlmUsage(0, 0, null);
      assertEquals(expectedUsage, result.usage());
    }
  }

  @Nested
  @DisplayName("失败路径")
  class FailurePath {

    @Test
    @DisplayName("工具调用 → PROTOCOL")
    void toolCalls() {
      var toolCallAssistant =
          AssistantMessage.builder()
              .content("I'll use a tool.")
              .toolCalls(
                  List.of(
                      new AssistantMessage.ToolCall(
                          "call_1", "function", "get_weather", "{\"city\":\"Paris\"}")))
              .build();
      var metadata = ChatGenerationMetadata.builder().finishReason("tool_use").build();
      var generation = new Generation(toolCallAssistant, metadata);
      when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(generation)));

      var exception =
          assertThrows(LlmProviderException.class, () -> adapter.generate(validRequest));

      assertEquals(LlmFailureCategory.PROTOCOL, exception.getCategory());
    }

    @Test
    @DisplayName("多个 Generation（含 thinking）→ 仅取最后一个，不触发协议错误")
    void multipleGenerationsWithThinking() {
      var thinking1 =
          AssistantMessage.builder()
              .content("thinking step 1")
              .properties(Map.of("signature", "sig1"))
              .build();
      var thinking2 =
          AssistantMessage.builder()
              .content("thinking step 2")
              .properties(Map.of("signature", "sig2"))
              .build();
      var textMessage = new AssistantMessage("Final answer.");
      var metadata = ChatGenerationMetadata.builder().finishReason("end_turn").build();
      var textGen = new Generation(textMessage, metadata);

      var usage = new DefaultUsage(10, 20, 30, null, null, null);
      var responseMeta = ChatResponseMetadata.builder().usage(usage).build();
      when(chatModel.call(any(Prompt.class)))
          .thenReturn(
              new ChatResponse(
                  List.of(new Generation(thinking1), new Generation(thinking2), textGen),
                  responseMeta));

      LlmResult result = adapter.generate(validRequest);

      assertEquals("Final answer.", result.answer());
    }

    @Test
    @DisplayName("空响应文本 → EMPTY_RESPONSE")
    void emptyResponse() {
      when(chatModel.call(any(Prompt.class))).thenReturn(createResponse("", "end_turn"));

      var exception =
          assertThrows(LlmProviderException.class, () -> adapter.generate(validRequest));

      assertEquals(LlmFailureCategory.EMPTY_RESPONSE, exception.getCategory());
    }

    @Test
    @DisplayName("空白响应文本 → EMPTY_RESPONSE")
    void blankResponse() {
      when(chatModel.call(any(Prompt.class))).thenReturn(createResponse("   ", "end_turn"));

      var exception =
          assertThrows(LlmProviderException.class, () -> adapter.generate(validRequest));

      assertEquals(LlmFailureCategory.EMPTY_RESPONSE, exception.getCategory());
    }

    @Test
    @DisplayName("无 Generation → EMPTY_RESPONSE")
    void noGenerations() {
      var responseMeta = ChatResponseMetadata.builder().build();
      when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(), responseMeta));

      var exception =
          assertThrows(LlmProviderException.class, () -> adapter.generate(validRequest));

      assertEquals(LlmFailureCategory.EMPTY_RESPONSE, exception.getCategory());
    }

    @Test
    @DisplayName("max_tokens 停止原因 → TRUNCATED_RESPONSE")
    void truncatedMaxTokens() {
      when(chatModel.call(any(Prompt.class)))
          .thenReturn(createResponse("Partial answer...", "max_tokens"));

      var exception =
          assertThrows(LlmProviderException.class, () -> adapter.generate(validRequest));

      assertEquals(LlmFailureCategory.TRUNCATED_RESPONSE, exception.getCategory());
    }

    @Test
    @DisplayName("tool_use 停止原因 → TRUNCATED_RESPONSE")
    void truncatedToolUse() {
      when(chatModel.call(any(Prompt.class))).thenReturn(createResponse("Some text.", "tool_use"));

      var exception =
          assertThrows(LlmProviderException.class, () -> adapter.generate(validRequest));

      assertEquals(LlmFailureCategory.TRUNCATED_RESPONSE, exception.getCategory());
    }

    @Test
    @DisplayName("SocketTimeoutException → TIMEOUT")
    void timeoutError() {
      when(chatModel.call(any(Prompt.class)))
          .thenThrow(new RuntimeException(new SocketTimeoutException("Read timed out")));

      var exception =
          assertThrows(LlmProviderException.class, () -> adapter.generate(validRequest));

      assertEquals(LlmFailureCategory.TIMEOUT, exception.getCategory());
    }

    @Test
    @DisplayName("UnauthorizedException → AUTHENTICATION")
    void authenticationError() {
      when(chatModel.call(any(Prompt.class)))
          .thenThrow(mock(com.anthropic.errors.UnauthorizedException.class));

      var exception =
          assertThrows(LlmProviderException.class, () -> adapter.generate(validRequest));

      assertEquals(LlmFailureCategory.AUTHENTICATION, exception.getCategory());
    }

    @Test
    @DisplayName("RateLimitException → RATE_LIMITED")
    void rateLimitError() {
      when(chatModel.call(any(Prompt.class)))
          .thenThrow(mock(com.anthropic.errors.RateLimitException.class));

      var exception =
          assertThrows(LlmProviderException.class, () -> adapter.generate(validRequest));

      assertEquals(LlmFailureCategory.RATE_LIMITED, exception.getCategory());
    }

    @Test
    @DisplayName("InternalServerException → SERVER_ERROR")
    void serverError() {
      when(chatModel.call(any(Prompt.class)))
          .thenThrow(mock(com.anthropic.errors.InternalServerException.class));

      var exception =
          assertThrows(LlmProviderException.class, () -> adapter.generate(validRequest));

      assertEquals(LlmFailureCategory.SERVER_ERROR, exception.getCategory());
    }

    @Test
    @DisplayName("未知异常 → UNKNOWN")
    void unknownError() {
      when(chatModel.call(any(Prompt.class)))
          .thenThrow(new RuntimeException("Something strange happened"));

      var exception =
          assertThrows(LlmProviderException.class, () -> adapter.generate(validRequest));

      assertEquals(LlmFailureCategory.UNKNOWN, exception.getCategory());
    }
  }
}
