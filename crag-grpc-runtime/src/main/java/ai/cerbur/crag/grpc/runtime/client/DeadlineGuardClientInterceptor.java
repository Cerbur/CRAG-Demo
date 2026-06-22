package ai.cerbur.crag.grpc.runtime.client;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import java.util.concurrent.TimeUnit;

public class DeadlineGuardClientInterceptor implements ClientInterceptor {

  private final long maxDeadlineMillis;

  public DeadlineGuardClientInterceptor(long maxDeadlineMillis) {
    this.maxDeadlineMillis = maxDeadlineMillis;
  }

  @Override
  public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
      MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
    if (callOptions.getDeadline() == null) {
      return new SimpleForwardingClientCall<>(next.newCall(method, callOptions)) {
        @Override
        public void start(Listener<RespT> responseListener, Metadata headers) {
          responseListener.onClose(
              Status.FAILED_PRECONDITION.withDescription(
                  "Missing deadline on call to " + method.getFullMethodName()),
              new Metadata());
        }
      };
    }
    if (callOptions.getDeadline().timeRemaining(TimeUnit.MILLISECONDS) > maxDeadlineMillis) {
      long remaining = callOptions.getDeadline().timeRemaining(TimeUnit.MILLISECONDS);
      return new SimpleForwardingClientCall<>(next.newCall(method, callOptions)) {
        @Override
        public void start(Listener<RespT> responseListener, Metadata headers) {
          responseListener.onClose(
              Status.INVALID_ARGUMENT.withDescription(
                  "Deadline " + remaining + " ms exceeds max " + maxDeadlineMillis + " ms"),
              new Metadata());
        }
      };
    }
    return next.newCall(method, callOptions);
  }
}
