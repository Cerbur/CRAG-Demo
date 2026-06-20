package ai.cerbur.crag.query.llm.config;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * DeepSeek Anthropic 协议组件测试 —— 使用 JDK {@link HttpServer} 验证 HTTP 请求协议.
 *
 * <p>启动本地 HTTP 服务器模拟 DeepSeek API 端点，验证：
 *
 * <ul>
 *   <li>请求方法、路径
 *   <li>x-api-key 和 anthropic-version 请求头
 *   <li>请求 JSON 中的 model、system、messages、temperature、max_tokens
 *   <li>零重试（仅发送一次请求）
 *   <li>DeepSeek fixture（thinking + text 响应）解析正确
 * </ul>
 */
class DeepSeekAnthropicProtocolComponentTest {

  private HttpServer server;
  private int port;
  private final BlockingQueue<CapturedRequest> capturedRequests = new LinkedBlockingQueue<>();
  private final ObjectMapper objectMapper = new ObjectMapper();

  /** 捕获的 HTTP 请求信息. */
  record CapturedRequest(String method, String path, String body, Headers headers) {

    record Headers(String contentType, String xApiKey, String anthropicVersion) {}
  }

  @BeforeEach
  void setUp() throws Exception {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    port = server.getAddress().getPort();
  }

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  @DisplayName("发送正确的协议请求头和 JSON 体")
  void sendsCorrectProtocol() throws Exception {
    startServer(jsonResponse("end_turn", "Test answer.", 10, 5));

    var options =
        AnthropicChatOptions.builder()
            .apiKey("sk-test-key-12345")
            .baseUrl("http://localhost:" + port)
            .model("deepseek-v4-flash")
            .temperature(0.0)
            .maxTokens(4096)
            .timeout(Duration.ofSeconds(10))
            .maxRetries(0)
            .build();

    var model = AnthropicChatModel.builder().options(options).build();
    var prompt =
        new Prompt(
            List.of(
                new SystemMessage("You are a helpful assistant."),
                new UserMessage("What is the capital of France?")));

    var result = model.call(prompt);
    assertNotNull(result);
    assertEquals("Test answer.", result.getResult().getOutput().getText());

    var captured = capturedRequests.poll(10, TimeUnit.SECONDS);
    assertNotNull(captured, "No request was captured by the server");

    // 验证请求方法和路径
    assertEquals("POST", captured.method());
    assertTrue(
        captured.path().contains("/v1/messages"),
        "Path should contain /v1/messages but was: " + captured.path());

    // 验证请求头
    assertNotNull(captured.headers(), "Headers should not be null");
    assertEquals("sk-test-key-12345", captured.headers().xApiKey());
    assertEquals("2023-06-01", captured.headers().anthropicVersion());
    assertTrue(
        captured.headers().contentType().startsWith("application/json"),
        "Content-Type should be application/json");

    // 验证请求 JSON 体
    JsonNode json = objectMapper.readTree(captured.body());
    assertEquals("deepseek-v4-flash", json.get("model").asText());
    assertEquals("You are a helpful assistant.", json.get("system").asText());
    assertEquals(0.0, json.get("temperature").asDouble(), 0.001);
    assertEquals(4096, json.get("max_tokens").asInt());

    // 验证 messages
    JsonNode messages = json.get("messages");
    assertNotNull(messages);
    assertTrue(messages.isArray());
    assertEquals(1, messages.size());
    assertEquals("user", messages.get(0).get("role").asText());
    assertTrue(messages.get(0).get("content").asText().contains("What is the capital of France?"));
  }

  @Test
  @DisplayName("零重试：仅发送一次请求")
  void zeroRetriesSendsOneRequest() throws Exception {
    // 使用 AtomicInteger 计数
    var callCount = new java.util.concurrent.atomic.AtomicInteger(0);
    server.createContext(
        "/",
        exchange -> {
          callCount.incrementAndGet();
          readBody(exchange);
          var response = jsonResponse("end_turn", "OK", 5, 3);
          sendResponse(exchange, 200, response);
        });
    server.setExecutor(Executors.newFixedThreadPool(1));
    server.start();

    var options =
        AnthropicChatOptions.builder()
            .apiKey("sk-test-key")
            .baseUrl("http://localhost:" + port)
            .model("deepseek-v4-flash")
            .temperature(0.0)
            .maxTokens(100)
            .timeout(Duration.ofSeconds(10))
            .maxRetries(0)
            .build();

    var model = AnthropicChatModel.builder().options(options).build();
    var prompt = new Prompt(List.of(new SystemMessage("System."), new UserMessage("User.")));

    model.call(prompt);

    // 等待请求处理完成
    Thread.sleep(500);
    assertEquals(1, callCount.get(), "Should send exactly 1 request with maxRetries=0");
  }

  @Test
  @DisplayName("DeepSeek fixture: thinking + text 响应，thinking 被忽略，text 被提取")
  void fixtureWithThinkingAndText() throws Exception {
    // 模拟 DeepSeek 返回 thinking + text 块
    String fixture =
        """
        {
          "id": "msg_test_001",
          "type": "message",
          "role": "assistant",
          "content": [
            {
              "type": "thinking",
              "thinking": "The user wants to know the capital of France. I recall that Paris is the capital of France and has been since the 10th century. This is a well-known fact.",
              "signature": "zK8FpjYC1E8dXxWv..."
            },
            {
              "type": "text",
              "text": "The capital of France is Paris."
            }
          ],
          "model": "deepseek-v4-flash",
          "stop_reason": "end_turn",
          "stop_sequence": null,
          "usage": {
            "input_tokens": 15,
            "output_tokens": 28
          }
        }
        """;

    startServer(fixture);

    var options =
        AnthropicChatOptions.builder()
            .apiKey("sk-test")
            .baseUrl("http://localhost:" + port)
            .model("deepseek-v4-flash")
            .temperature(0.0)
            .maxTokens(4096)
            .timeout(Duration.ofSeconds(10))
            .maxRetries(0)
            .build();

    var model = AnthropicChatModel.builder().options(options).build();
    var prompt =
        new Prompt(
            List.of(
                new SystemMessage("You are a helpful assistant."),
                new UserMessage("What is the capital of France?")));

    var result = model.call(prompt);

    // 验证：Spring AI 解析了 thinking 块为独立 Generation，text 块为最后一个 Generation
    assertNotNull(result);
    var generations = result.getResults();
    // 至少有一个 Generation（如果 thinking 和 text 都解析，可能有多个）
    assertFalse(generations.isEmpty(), "Should have at least one generation");

    // 最后一个 Generation 应为 text 内容
    var lastGen = generations.get(generations.size() - 1);
    var text = lastGen.getOutput().getText();
    assertEquals("The capital of France is Paris.", text);

    // 验证 usage
    var metadata = result.getMetadata();
    assertNotNull(metadata);
    assertNotNull(metadata.getUsage());
    assertEquals(Integer.valueOf(15), metadata.getUsage().getPromptTokens());
    assertEquals(Integer.valueOf(28), metadata.getUsage().getCompletionTokens());
  }

  // ---- Test helpers ----

  /** 启动 HTTP 服务器，返回固定 JSON 响应. */
  private void startServer(String responseBody) {
    server.createContext(
        "/",
        exchange -> {
          try {
            var body = readBody(exchange);
            var method = exchange.getRequestMethod();
            var path = exchange.getRequestURI().toString();

            var headers = exchange.getRequestHeaders();
            var contentType = headers.getFirst("Content-Type");
            var xApiKey = headers.getFirst("x-api-key");
            var anthropicVersion = headers.getFirst("anthropic-version");

            capturedRequests.add(
                new CapturedRequest(
                    method,
                    path,
                    body,
                    new CapturedRequest.Headers(contentType, xApiKey, anthropicVersion)));

            sendResponse(exchange, 200, responseBody);
          } catch (Exception e) {
            sendResponse(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
          }
        });
    server.setExecutor(Executors.newFixedThreadPool(1));
    server.start();
  }

  private static String readBody(HttpExchange exchange) throws IOException {
    var in = exchange.getRequestBody();
    var buffer = new ByteArrayOutputStream();
    byte[] data = new byte[4096];
    int n;
    while ((n = in.read(data)) != -1) {
      buffer.write(data, 0, n);
    }
    return buffer.toString(StandardCharsets.UTF_8);
  }

  private static void sendResponse(HttpExchange exchange, int statusCode, String responseBody)
      throws IOException {
    var bytes = responseBody.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(statusCode, bytes.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(bytes);
    }
  }

  /** 构建标准 Anthropic JSON 响应. */
  private static String jsonResponse(
      String stopReason, String text, int inputTokens, int outputTokens) {
    return """
        {
          "id": "msg_test",
          "type": "message",
          "role": "assistant",
          "content": [
            {
              "type": "text",
              "text": "%s"
            }
          ],
          "model": "deepseek-v4-flash",
          "stop_reason": "%s",
          "stop_sequence": null,
          "usage": {
            "input_tokens": %d,
            "output_tokens": %d
          }
        }
        """
        .formatted(text, stopReason, inputTokens, outputTokens)
        .stripIndent();
  }
}
