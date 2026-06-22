package ai.cerbur.crag.grpc.runtime.server;

import io.grpc.Server;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.services.HealthStatusManager;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

public class GrpcServerLifecycle implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(GrpcServerLifecycle.class);

  private final Server server;
  private final HealthStatusManager healthStatusManager;
  private final long shutdownTimeoutMillis;
  private volatile boolean running;

  public GrpcServerLifecycle(
      Server server, HealthStatusManager healthStatusManager, long shutdownTimeoutMillis) {
    this.server = server;
    this.healthStatusManager = healthStatusManager;
    this.shutdownTimeoutMillis = shutdownTimeoutMillis;
  }

  @Override
  public void start() {
    try {
      server.start();
      healthStatusManager.setStatus("", HealthCheckResponse.ServingStatus.SERVING);
      running = true;
      log.info("gRPC server started on port {}", server.getPort());
    } catch (IOException e) {
      throw new IllegalStateException("Failed to start gRPC server", e);
    }
  }

  @Override
  public void stop() {
    stop(null);
  }

  @Override
  public void stop(Runnable callback) {
    if (!running) {
      if (callback != null) {
        callback.run();
      }
      return;
    }
    healthStatusManager.setStatus("", HealthCheckResponse.ServingStatus.NOT_SERVING);
    try {
      server.shutdown();
      if (!server.awaitTermination(shutdownTimeoutMillis, TimeUnit.MILLISECONDS)) {
        log.warn(
            "gRPC server did not terminate within {} ms, forcing shutdown", shutdownTimeoutMillis);
        server.shutdownNow();
      }
    } catch (InterruptedException e) {
      server.shutdownNow();
      Thread.currentThread().interrupt();
    }
    running = false;
    log.info("gRPC server stopped");
    if (callback != null) {
      callback.run();
    }
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  @Override
  public int getPhase() {
    return Integer.MAX_VALUE;
  }
}
