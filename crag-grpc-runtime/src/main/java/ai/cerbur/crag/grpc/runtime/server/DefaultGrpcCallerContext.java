package ai.cerbur.crag.grpc.runtime.server;

import ai.cerbur.crag.grpc.runtime.identity.GrpcCallerContext;
import ai.cerbur.crag.grpc.runtime.identity.GrpcCallerIdentity;

public class DefaultGrpcCallerContext implements GrpcCallerContext {

  @Override
  public GrpcCallerIdentity requireIdentity() {
    GrpcCallerIdentity identity = GrpcServiceAuthenticationInterceptor.CALLER_IDENTITY_KEY.get();
    if (identity == null) {
      throw new IllegalStateException("No authenticated caller identity in current context");
    }
    return identity;
  }
}
