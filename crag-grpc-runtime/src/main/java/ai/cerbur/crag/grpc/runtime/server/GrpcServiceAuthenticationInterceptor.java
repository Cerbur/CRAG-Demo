package ai.cerbur.crag.grpc.runtime.server;

import ai.cerbur.crag.grpc.runtime.identity.GrpcCallerIdentity;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GrpcServiceAuthenticationInterceptor implements ServerInterceptor {

  private static final Logger log =
      LoggerFactory.getLogger(GrpcServiceAuthenticationInterceptor.class);

  public static final Metadata.Key<String> CALLER_SERVICE_KEY =
      Metadata.Key.of("x-crag-caller-service", Metadata.ASCII_STRING_MARSHALLER);
  public static final Metadata.Key<String> SERVICE_TOKEN_KEY =
      Metadata.Key.of("x-crag-service-token", Metadata.ASCII_STRING_MARSHALLER);

  public static final Context.Key<GrpcCallerIdentity> CALLER_IDENTITY_KEY =
      Context.key("crag-caller-identity");

  private final Map<String, byte[]> allowedCallers;

  public GrpcServiceAuthenticationInterceptor(Map<String, String> allowedCallers) {
    if (allowedCallers == null || allowedCallers.isEmpty()) {
      throw new IllegalArgumentException("allowedCallers must not be empty");
    }
    this.allowedCallers = new java.util.LinkedHashMap<>();
    allowedCallers.forEach(
        (caller, token) -> {
          if (caller == null || caller.isBlank()) {
            throw new IllegalArgumentException("caller name must not be blank");
          }
          if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("token for caller " + caller + " must not be blank");
          }
          this.allowedCallers.put(caller, token.getBytes(StandardCharsets.UTF_8));
        });
  }

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
    String callerService = headers.get(CALLER_SERVICE_KEY);
    String serviceToken = headers.get(SERVICE_TOKEN_KEY);

    if (callerService == null || callerService.isBlank()) {
      log.warn("Missing caller service identity");
      call.close(Status.UNAUTHENTICATED.withDescription("Missing caller identity"), headers);
      return new ServerCall.Listener<>() {};
    }

    if (serviceToken == null || serviceToken.isBlank()) {
      log.warn("Missing service token from caller: {}", callerService);
      call.close(Status.UNAUTHENTICATED.withDescription("Missing service token"), headers);
      return new ServerCall.Listener<>() {};
    }

    byte[] expectedToken = allowedCallers.get(callerService);
    if (expectedToken == null) {
      log.warn("Unknown caller: {}", callerService);
      call.close(Status.UNAUTHENTICATED.withDescription("Unknown caller"), headers);
      return new ServerCall.Listener<>() {};
    }

    byte[] providedToken = serviceToken.getBytes(StandardCharsets.UTF_8);
    if (!constantTimeEquals(expectedToken, providedToken)) {
      log.warn("Token mismatch for caller: {}", callerService);
      call.close(Status.UNAUTHENTICATED.withDescription("Invalid token"), headers);
      return new ServerCall.Listener<>() {};
    }

    GrpcCallerIdentity identity = new GrpcCallerIdentity(callerService);
    Context ctx = Context.current().withValue(CALLER_IDENTITY_KEY, identity);
    return Contexts.interceptCall(ctx, call, headers, next);
  }

  public static boolean constantTimeEquals(byte[] a, byte[] b) {
    int result = 0;
    int maxLen = Math.max(a.length, b.length);
    for (int i = 0; i < maxLen; i++) {
      int ai = i < a.length ? (a[i] & 0xFF) : 0;
      int bi = i < b.length ? (b[i] & 0xFF) : 0;
      result |= ai ^ bi;
    }
    result |= a.length ^ b.length;
    return result == 0;
  }
}
