package ai.cerbur.crag.console.probe;

import ai.cerbur.crag.contracts.platform.v1.PlatformProbeRequest;
import ai.cerbur.crag.contracts.platform.v1.PlatformProbeResponse;
import ai.cerbur.crag.contracts.platform.v1.PlatformProbeServiceGrpc;
import ai.cerbur.crag.grpc.runtime.client.GrpcClientProperties;
import io.grpc.StatusRuntimeException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

@Component("downstreamConnectivity")
public class DownstreamConnectivityHealthIndicator implements HealthIndicator {

  private static final long PROBE_DEADLINE_MILLIS = 2000;
  private static final long TOTAL_BUDGET_MILLIS = 3000;

  @Autowired
  private Map<String, PlatformProbeServiceGrpc.PlatformProbeServiceBlockingStub> probeStubs;

  @Autowired
  @Qualifier("probeExecutor")
  private ThreadPoolTaskExecutor executor;

  @Autowired private GrpcClientProperties clientProperties;

  @Override
  public Health health() {
    String callerService = clientProperties.getCallerService();
    Map<String, Future<String>> futures = new LinkedHashMap<>();
    for (Map.Entry<String, PlatformProbeServiceGrpc.PlatformProbeServiceBlockingStub> entry :
        probeStubs.entrySet()) {
      String target = entry.getKey();
      PlatformProbeServiceGrpc.PlatformProbeServiceBlockingStub stub = entry.getValue();
      futures.put(
          target,
          executor.submit(
              () -> {
                try {
                  PlatformProbeResponse resp =
                      stub.withDeadlineAfter(PROBE_DEADLINE_MILLIS, TimeUnit.MILLISECONDS)
                          .check(PlatformProbeRequest.getDefaultInstance());
                  if (!target.equals(resp.getServiceName())) {
                    return "DOWN";
                  }
                  if (!callerService.equals(resp.getCallerService())) {
                    return "DOWN";
                  }
                  return "UP";
                } catch (StatusRuntimeException e) {
                  if (e.getStatus().getCode() == io.grpc.Status.Code.UNAUTHENTICATED
                      || e.getStatus().getCode() == io.grpc.Status.Code.PERMISSION_DENIED) {
                    return "UNAUTHENTICATED";
                  }
                  if (e.getStatus().getCode() == io.grpc.Status.Code.DEADLINE_EXCEEDED) {
                    return "TIMEOUT";
                  }
                  return "DOWN";
                } catch (Exception e) {
                  return "DOWN";
                }
              }));
    }

    Map<String, String> results = new LinkedHashMap<>();
    boolean allUp = true;
    long deadline = System.currentTimeMillis() + TOTAL_BUDGET_MILLIS;
    List<Future<String>> pendingFutures = new ArrayList<>();

    for (Map.Entry<String, Future<String>> entry : futures.entrySet()) {
      long remaining = deadline - System.currentTimeMillis();
      try {
        String result = entry.getValue().get(Math.max(remaining, 0), TimeUnit.MILLISECONDS);
        results.put(entry.getKey(), result);
        if (!"UP".equals(result)) {
          allUp = false;
        }
      } catch (Exception e) {
        pendingFutures.add(entry.getValue());
        results.put(entry.getKey(), "TIMEOUT");
        allUp = false;
      }
    }

    for (Future<String> f : pendingFutures) {
      f.cancel(true);
    }

    Health.Builder builder = allUp ? Health.up() : Health.down();
    results.forEach(builder::withDetail);
    return builder.build();
  }
}
