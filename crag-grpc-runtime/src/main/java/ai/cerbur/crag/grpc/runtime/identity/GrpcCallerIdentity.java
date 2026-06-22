package ai.cerbur.crag.grpc.runtime.identity;

public record GrpcCallerIdentity(String serviceName) {
  public GrpcCallerIdentity {
    if (serviceName == null || serviceName.isBlank()) {
      throw new IllegalArgumentException("serviceName must not be blank");
    }
  }
}
