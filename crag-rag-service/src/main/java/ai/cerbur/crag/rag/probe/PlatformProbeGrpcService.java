package ai.cerbur.crag.rag.probe;

import ai.cerbur.crag.contracts.platform.v1.PlatformProbeRequest;
import ai.cerbur.crag.contracts.platform.v1.PlatformProbeResponse;
import ai.cerbur.crag.contracts.platform.v1.PlatformProbeServiceGrpc;
import ai.cerbur.crag.grpc.runtime.identity.GrpcCallerContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class PlatformProbeGrpcService
    extends PlatformProbeServiceGrpc.PlatformProbeServiceImplBase {

  @Autowired private GrpcCallerContext callerContext;

  @Override
  public void check(
      PlatformProbeRequest request,
      io.grpc.stub.StreamObserver<PlatformProbeResponse> responseObserver) {
    String callerName = callerContext.requireIdentity().serviceName();
    responseObserver.onNext(
        PlatformProbeResponse.newBuilder()
            .setServiceName("rag-service")
            .setCallerService(callerName)
            .build());
    responseObserver.onCompleted();
  }
}
