package ai.cerbur.crag.grpc.runtime;

import static org.junit.jupiter.api.Assertions.*;

import ai.cerbur.crag.grpc.runtime.client.DeadlineGuardClientInterceptor;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.Deadline;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DeadlineGuardClientInterceptorTest {

  private static final long MAX_DEADLINE_MILLIS = 10000;

  @SuppressWarnings("unchecked")
  private static MethodDescriptor<Object, Object> mockMethod() {
    return (MethodDescriptor<Object, Object>) org.mockito.Mockito.mock(MethodDescriptor.class);
  }

  @SuppressWarnings("unchecked")
  private static Channel mockChannel(ClientCall<Object, Object> callToReturn) {
    Channel channel = org.mockito.Mockito.mock(Channel.class);
    org.mockito.Mockito.when(
            channel.newCall(
                org.mockito.Mockito.any(MethodDescriptor.class),
                org.mockito.Mockito.any(CallOptions.class)))
        .thenReturn((ClientCall) callToReturn);
    return channel;
  }

  @SuppressWarnings("unchecked")
  private static ClientCall<Object, Object> mockClientCall() {
    return (ClientCall<Object, Object>) org.mockito.Mockito.mock(ClientCall.class);
  }

  @Test
  @DisplayName("缺少 deadline 时拒绝调用")
  void missingDeadline_rejects() {
    var interceptor = new DeadlineGuardClientInterceptor(MAX_DEADLINE_MILLIS);
    MethodDescriptor<Object, Object> method = mockMethod();
    org.mockito.Mockito.when(method.getFullMethodName()).thenReturn("test/Method");
    Channel channel = mockChannel(mockClientCall());

    CallOptions opts = CallOptions.DEFAULT;
    ClientCall<Object, Object> call = interceptor.interceptCall(method, opts, channel);

    ClientCall.Listener<Object> listener =
        (ClientCall.Listener<Object>) org.mockito.Mockito.mock(ClientCall.Listener.class);
    call.start(listener, new io.grpc.Metadata());

    ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(Status.class);
    org.mockito.Mockito.verify(listener).onClose(statusCaptor.capture(), org.mockito.Mockito.any());
    assertEquals(Status.Code.FAILED_PRECONDITION, statusCaptor.getValue().getCode());
    assertTrue(statusCaptor.getValue().getDescription().contains("Missing deadline"));
  }

  @Test
  @DisplayName("deadline 超过最大值时拒绝调用")
  void deadlineExceedsMax_rejects() {
    var interceptor = new DeadlineGuardClientInterceptor(MAX_DEADLINE_MILLIS);
    MethodDescriptor<Object, Object> method = mockMethod();
    org.mockito.Mockito.when(method.getFullMethodName()).thenReturn("test/Method");
    Channel channel = mockChannel(mockClientCall());

    CallOptions opts = CallOptions.DEFAULT.withDeadline(Deadline.after(30, TimeUnit.SECONDS));
    ClientCall<Object, Object> call = interceptor.interceptCall(method, opts, channel);

    ClientCall.Listener<Object> listener =
        (ClientCall.Listener<Object>) org.mockito.Mockito.mock(ClientCall.Listener.class);
    call.start(listener, new io.grpc.Metadata());

    ArgumentCaptor<Status> statusCaptor = ArgumentCaptor.forClass(Status.class);
    org.mockito.Mockito.verify(listener).onClose(statusCaptor.capture(), org.mockito.Mockito.any());
    assertEquals(Status.Code.INVALID_ARGUMENT, statusCaptor.getValue().getCode());
    assertTrue(statusCaptor.getValue().getDescription().contains("exceeds max"));
  }

  @Test
  @DisplayName("合法 deadline 时允许调用通过")
  void validDeadline_passes() {
    var interceptor = new DeadlineGuardClientInterceptor(MAX_DEADLINE_MILLIS);
    MethodDescriptor<Object, Object> method = mockMethod();
    ClientCall<Object, Object> innerCall = mockClientCall();
    Channel channel = mockChannel(innerCall);

    CallOptions opts = CallOptions.DEFAULT.withDeadline(Deadline.after(5, TimeUnit.SECONDS));
    ClientCall<Object, Object> result = interceptor.interceptCall(method, opts, channel);

    assertSame(innerCall, result);
  }
}
