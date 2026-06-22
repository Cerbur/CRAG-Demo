package ai.cerbur.crag.grpc.runtime.client;

import io.grpc.ManagedChannel;

public interface GrpcChannelFactory {
  ManagedChannel create(String targetName, String target, boolean plaintext);
}
