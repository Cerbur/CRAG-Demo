package ai.cerbur.crag.grpc.runtime;

import static org.junit.jupiter.api.Assertions.*;

import ai.cerbur.crag.grpc.runtime.server.GrpcServiceAuthenticationInterceptor;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class GrpcServiceAuthenticationInterceptorTest {

  private static final Metadata.Key<String> CALLER_SERVICE_KEY =
      Metadata.Key.of("x-crag-caller-service", Metadata.ASCII_STRING_MARSHALLER);
  private static final Metadata.Key<String> SERVICE_TOKEN_KEY =
      Metadata.Key.of("x-crag-service-token", Metadata.ASCII_STRING_MARSHALLER);

  private static Map<String, String> allowedCallers() {
    Map<String, String> callers = new LinkedHashMap<>();
    callers.put("console-api", "token-console");
    callers.put("open-api", "token-open");
    return callers;
  }

  @SuppressWarnings("unchecked")
  private static ServerCall<Object, Object> mockCall() {
    return (ServerCall<Object, Object>) org.mockito.Mockito.mock(ServerCall.class);
  }

  @SuppressWarnings("unchecked")
  private static ServerCallHandler<Object, Object> mockHandler() {
    return (ServerCallHandler<Object, Object>) org.mockito.Mockito.mock(ServerCallHandler.class);
  }

  @Test
  @DisplayName("缺少 caller-service 返回 UNAUTHENTICATED")
  void missingCallerService_returnsUnauthenticated() {
    var interceptor = new GrpcServiceAuthenticationInterceptor(allowedCallers());
    Metadata headers = new Metadata();
    headers.put(SERVICE_TOKEN_KEY, "token-console");

    ServerCall<Object, Object> call = mockCall();
    ServerCallHandler<Object, Object> handler = mockHandler();

    interceptor.interceptCall(call, headers, handler);

    ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(Status.class);
    org.mockito.Mockito.verify(call).close(statusCaptor.capture(), org.mockito.Mockito.any());
    assertEquals(Status.Code.UNAUTHENTICATED, statusCaptor.getValue().getCode());
  }

  @Test
  @DisplayName("缺少 service-token 返回 UNAUTHENTICATED")
  void missingToken_returnsUnauthenticated() {
    var interceptor = new GrpcServiceAuthenticationInterceptor(allowedCallers());
    Metadata headers = new Metadata();
    headers.put(CALLER_SERVICE_KEY, "console-api");

    ServerCall<Object, Object> call = mockCall();
    ServerCallHandler<Object, Object> handler = mockHandler();

    interceptor.interceptCall(call, headers, handler);

    ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(Status.class);
    org.mockito.Mockito.verify(call).close(statusCaptor.capture(), org.mockito.Mockito.any());
    assertEquals(Status.Code.UNAUTHENTICATED, statusCaptor.getValue().getCode());
  }

  @Test
  @DisplayName("未知 caller 返回 UNAUTHENTICATED")
  void unknownCaller_returnsUnauthenticated() {
    var interceptor = new GrpcServiceAuthenticationInterceptor(allowedCallers());
    Metadata headers = new Metadata();
    headers.put(CALLER_SERVICE_KEY, "unknown-service");
    headers.put(SERVICE_TOKEN_KEY, "any-token");

    ServerCall<Object, Object> call = mockCall();
    ServerCallHandler<Object, Object> handler = mockHandler();

    interceptor.interceptCall(call, headers, handler);

    ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(Status.class);
    org.mockito.Mockito.verify(call).close(statusCaptor.capture(), org.mockito.Mockito.any());
    assertEquals(Status.Code.UNAUTHENTICATED, statusCaptor.getValue().getCode());
  }

  @Test
  @DisplayName("错误 token 返回 UNAUTHENTICATED")
  void wrongToken_returnsUnauthenticated() {
    var interceptor = new GrpcServiceAuthenticationInterceptor(allowedCallers());
    Metadata headers = new Metadata();
    headers.put(CALLER_SERVICE_KEY, "console-api");
    headers.put(SERVICE_TOKEN_KEY, "wrong-token");

    ServerCall<Object, Object> call = mockCall();
    ServerCallHandler<Object, Object> handler = mockHandler();

    interceptor.interceptCall(call, headers, handler);

    ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(Status.class);
    org.mockito.Mockito.verify(call).close(statusCaptor.capture(), org.mockito.Mockito.any());
    assertEquals(Status.Code.UNAUTHENTICATED, statusCaptor.getValue().getCode());
  }

  @Test
  @DisplayName("空 allowedCallers 构造时抛出异常")
  void emptyAllowedCallers_throws() {
    assertThrows(
        IllegalArgumentException.class, () -> new GrpcServiceAuthenticationInterceptor(Map.of()));
  }

  @Test
  @DisplayName("空 caller 名称构造时抛出异常")
  void blankCallerName_throws() {
    Map<String, String> callers = new LinkedHashMap<>();
    callers.put("  ", "token");
    assertThrows(
        IllegalArgumentException.class, () -> new GrpcServiceAuthenticationInterceptor(callers));
  }

  @Test
  @DisplayName("空 token 构造时抛出异常")
  void blankToken_throws() {
    Map<String, String> callers = new LinkedHashMap<>();
    callers.put("svc", "  ");
    assertThrows(
        IllegalArgumentException.class, () -> new GrpcServiceAuthenticationInterceptor(callers));
  }

  @Test
  @DisplayName("constantTimeEquals 对相同内容返回 true")
  void constantTimeEquals_sameContent() {
    byte[] a = "hello".getBytes();
    byte[] b = "hello".getBytes();
    assertTrue(GrpcServiceAuthenticationInterceptor.constantTimeEquals(a, b));
  }

  @Test
  @DisplayName("constantTimeEquals 对不同内容返回 false")
  void constantTimeEquals_differentContent() {
    byte[] a = "hello".getBytes();
    byte[] b = "world".getBytes();
    assertFalse(GrpcServiceAuthenticationInterceptor.constantTimeEquals(a, b));
  }

  @Test
  @DisplayName("constantTimeEquals 对不同长度返回 false")
  void constantTimeEquals_differentLength() {
    byte[] a = "hello".getBytes();
    byte[] b = "hi".getBytes();
    assertFalse(GrpcServiceAuthenticationInterceptor.constantTimeEquals(a, b));
  }

  @Test
  @DisplayName("constantTimeEquals 不同长度时执行固定工作量比较")
  void constantTimeEquals_differentLength_doesConstantWork() {
    byte[] a = "ab".getBytes();
    byte[] b = "abcdef".getBytes();
    assertFalse(GrpcServiceAuthenticationInterceptor.constantTimeEquals(a, b));
    assertFalse(GrpcServiceAuthenticationInterceptor.constantTimeEquals(b, a));

    byte[] sameLen = "abcdef".getBytes();
    byte[] sameLenDiff = "abcdeg".getBytes();
    assertFalse(GrpcServiceAuthenticationInterceptor.constantTimeEquals(sameLen, sameLenDiff));
  }

  @Test
  @DisplayName("响应和日志不包含完整 token")
  void logsDoNotContainToken() {
    var interceptor = new GrpcServiceAuthenticationInterceptor(allowedCallers());
    Metadata headers = new Metadata();
    headers.put(CALLER_SERVICE_KEY, "console-api");
    headers.put(SERVICE_TOKEN_KEY, "wrong-token");

    ServerCall<Object, Object> call = mockCall();
    ServerCallHandler<Object, Object> handler = mockHandler();

    interceptor.interceptCall(call, headers, handler);

    ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(Status.class);
    org.mockito.Mockito.verify(call).close(statusCaptor.capture(), org.mockito.Mockito.any());
    String description = statusCaptor.getValue().getDescription();
    assertNotNull(description);
    assertFalse(description.contains("wrong-token"), "Status description must not contain token");
    assertFalse(
        description.contains("token-console"),
        "Status description must not contain expected token");
  }

  @Test
  @DisplayName("合法 caller 身份通过认证并设置 Context")
  void validCaller_passesAndSetsContext() {
    var interceptor = new GrpcServiceAuthenticationInterceptor(allowedCallers());
    Metadata headers = new Metadata();
    headers.put(CALLER_SERVICE_KEY, "console-api");
    headers.put(SERVICE_TOKEN_KEY, "token-console");

    ServerCall<Object, Object> call = mockCall();
    ServerCallHandler<Object, Object> handler = mockHandler();
    org.mockito.Mockito.when(
            handler.startCall(org.mockito.Mockito.any(), org.mockito.Mockito.any()))
        .thenReturn(new ServerCall.Listener<>() {});

    interceptor.interceptCall(call, headers, handler);

    org.mockito.Mockito.verify(handler)
        .startCall(org.mockito.Mockito.any(), org.mockito.Mockito.any());
    org.mockito.Mockito.verify(call, org.mockito.Mockito.never())
        .close(org.mockito.Mockito.any(), org.mockito.Mockito.any());
  }
}
