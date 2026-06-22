package ai.cerbur.crag.grpc.runtime;

import ai.cerbur.crag.grpc.runtime.server.GrpcServiceAuthenticationInterceptor;
import io.grpc.MethodDescriptor;
import io.grpc.ServerServiceDefinition;
import io.grpc.stub.ServerCalls;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Hand-coded gRPC service for testing. Does not depend on protobuf-generated code. Returns the
 * authenticated caller's service name.
 */
class EchoService implements io.grpc.BindableService {

  static final MethodDescriptor<EchoRequest, EchoResponse> ECHO_METHOD =
      MethodDescriptor.<EchoRequest, EchoResponse>newBuilder()
          .setType(MethodDescriptor.MethodType.UNARY)
          .setFullMethodName("test.EchoService/Echo")
          .setRequestMarshaller(new EchoRequestMarshaller())
          .setResponseMarshaller(new EchoResponseMarshaller())
          .build();

  @Override
  public ServerServiceDefinition bindService() {
    return ServerServiceDefinition.builder("test.EchoService")
        .addMethod(
            ECHO_METHOD,
            ServerCalls.asyncUnaryCall(
                (request, responseObserver) -> {
                  var identity = GrpcServiceAuthenticationInterceptor.CALLER_IDENTITY_KEY.get();
                  String callerName = identity != null ? identity.serviceName() : "anonymous";
                  responseObserver.onNext(new EchoResponse(callerName));
                  responseObserver.onCompleted();
                }))
        .build();
  }

  static class EchoRequest {
    static final EchoRequest INSTANCE = new EchoRequest();
  }

  static class EchoResponse {
    final String callerService;

    EchoResponse(String callerService) {
      this.callerService = callerService;
    }
  }

  static class EchoBlockingStub extends io.grpc.stub.AbstractBlockingStub<EchoBlockingStub> {
    EchoBlockingStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @Override
    protected EchoBlockingStub build(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new EchoBlockingStub(channel, callOptions);
    }

    EchoResponse echo(EchoRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), ECHO_METHOD, getCallOptions(), request);
    }
  }

  static EchoBlockingStub newBlockingStub(io.grpc.Channel channel) {
    return new EchoBlockingStub(channel, io.grpc.CallOptions.DEFAULT);
  }

  private static class EchoRequestMarshaller implements MethodDescriptor.Marshaller<EchoRequest> {
    @Override
    public InputStream stream(EchoRequest value) {
      return new ByteArrayInputStream(new byte[0]);
    }

    @Override
    public EchoRequest parse(InputStream stream) {
      return EchoRequest.INSTANCE;
    }
  }

  private static class EchoResponseMarshaller implements MethodDescriptor.Marshaller<EchoResponse> {
    @Override
    public InputStream stream(EchoResponse value) {
      return new ByteArrayInputStream(value.callerService.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public EchoResponse parse(InputStream stream) {
      try {
        String caller = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        return new EchoResponse(caller);
      } catch (java.io.IOException e) {
        throw new RuntimeException(e);
      }
    }
  }
}
