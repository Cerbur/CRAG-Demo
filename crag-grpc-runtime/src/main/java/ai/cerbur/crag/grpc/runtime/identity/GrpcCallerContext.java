package ai.cerbur.crag.grpc.runtime.identity;

public interface GrpcCallerContext {
  GrpcCallerIdentity requireIdentity();
}
