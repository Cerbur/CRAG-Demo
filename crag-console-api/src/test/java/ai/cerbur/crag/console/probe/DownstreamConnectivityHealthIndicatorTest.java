package ai.cerbur.crag.console.probe;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import ai.cerbur.crag.contracts.platform.v1.PlatformProbeRequest;
import ai.cerbur.crag.contracts.platform.v1.PlatformProbeResponse;
import ai.cerbur.crag.contracts.platform.v1.PlatformProbeServiceGrpc;
import io.grpc.StatusRuntimeException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.health.contributor.Health;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@ExtendWith(MockitoExtension.class)
class DownstreamConnectivityHealthIndicatorTest {

  @Mock private PlatformProbeServiceGrpc.PlatformProbeServiceBlockingStub accessStub;
  @Mock private PlatformProbeServiceGrpc.PlatformProbeServiceBlockingStub ragStub;

  private ThreadPoolTaskExecutor executor;
  private DownstreamConnectivityHealthIndicator indicator;

  @BeforeEach
  void setUp() {
    executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(3);
    executor.setMaxPoolSize(3);
    executor.setQueueCapacity(0);
    executor.setThreadNamePrefix("probe-test-");
    executor.initialize();

    indicator = new DownstreamConnectivityHealthIndicator();
    org.springframework.test.util.ReflectionTestUtils.setField(indicator, "executor", executor);
  }

  @Test
  @DisplayName("全部 Probe 成功时 readiness 为 UP")
  void allProbesSuccess() {
    Map<String, PlatformProbeServiceGrpc.PlatformProbeServiceBlockingStub> stubs =
        new LinkedHashMap<>();
    stubs.put("access-service", accessStub);
    stubs.put("rag-service", ragStub);
    org.springframework.test.util.ReflectionTestUtils.setField(indicator, "probeStubs", stubs);

    PlatformProbeResponse response =
        PlatformProbeResponse.newBuilder()
            .setServiceName("access-service")
            .setCallerService("console-api")
            .build();
    when(accessStub.withDeadlineAfter(anyLong(), any(TimeUnit.class))).thenReturn(accessStub);
    when(accessStub.check(any(PlatformProbeRequest.class))).thenReturn(response);
    when(ragStub.withDeadlineAfter(anyLong(), any(TimeUnit.class))).thenReturn(ragStub);
    when(ragStub.check(any(PlatformProbeRequest.class))).thenReturn(response);

    Health health = indicator.health();
    assertEquals("UP", health.getDetails().get("access-service"));
    assertEquals("UP", health.getDetails().get("rag-service"));
  }

  @Test
  @DisplayName("单目标失败时 readiness 为 DOWN")
  void singleTargetFailure() {
    Map<String, PlatformProbeServiceGrpc.PlatformProbeServiceBlockingStub> stubs =
        new LinkedHashMap<>();
    stubs.put("access-service", accessStub);
    stubs.put("rag-service", ragStub);
    org.springframework.test.util.ReflectionTestUtils.setField(indicator, "probeStubs", stubs);

    PlatformProbeResponse response =
        PlatformProbeResponse.newBuilder()
            .setServiceName("access-service")
            .setCallerService("console-api")
            .build();
    when(accessStub.withDeadlineAfter(anyLong(), any(TimeUnit.class))).thenReturn(accessStub);
    when(accessStub.check(any(PlatformProbeRequest.class))).thenReturn(response);
    when(ragStub.withDeadlineAfter(anyLong(), any(TimeUnit.class))).thenReturn(ragStub);
    when(ragStub.check(any(PlatformProbeRequest.class)))
        .thenThrow(new StatusRuntimeException(io.grpc.Status.UNAVAILABLE));

    Health health = indicator.health();
    assertEquals("UP", health.getDetails().get("access-service"));
    assertEquals("DOWN", health.getDetails().get("rag-service"));
  }

  @Test
  @DisplayName("鉴权失败返回 UNAUTHENTICATED")
  void authenticationFailure() {
    Map<String, PlatformProbeServiceGrpc.PlatformProbeServiceBlockingStub> stubs =
        new LinkedHashMap<>();
    stubs.put("access-service", accessStub);
    org.springframework.test.util.ReflectionTestUtils.setField(indicator, "probeStubs", stubs);

    when(accessStub.withDeadlineAfter(anyLong(), any(TimeUnit.class))).thenReturn(accessStub);
    when(accessStub.check(any(PlatformProbeRequest.class)))
        .thenThrow(new StatusRuntimeException(io.grpc.Status.UNAUTHENTICATED));

    Health health = indicator.health();
    assertEquals("UNAUTHENTICATED", health.getDetails().get("access-service"));
  }

  @Test
  @DisplayName("deadline 超时返回 TIMEOUT")
  void deadlineExceeded() {
    Map<String, PlatformProbeServiceGrpc.PlatformProbeServiceBlockingStub> stubs =
        new LinkedHashMap<>();
    stubs.put("access-service", accessStub);
    org.springframework.test.util.ReflectionTestUtils.setField(indicator, "probeStubs", stubs);

    when(accessStub.withDeadlineAfter(anyLong(), any(TimeUnit.class))).thenReturn(accessStub);
    when(accessStub.check(any(PlatformProbeRequest.class)))
        .thenThrow(new StatusRuntimeException(io.grpc.Status.DEADLINE_EXCEEDED));

    Health health = indicator.health();
    assertEquals("TIMEOUT", health.getDetails().get("access-service"));
  }

  @Test
  @DisplayName("details 不泄漏 token")
  void detailsDoNotLeakToken() {
    Map<String, PlatformProbeServiceGrpc.PlatformProbeServiceBlockingStub> stubs =
        new LinkedHashMap<>();
    stubs.put("access-service", accessStub);
    org.springframework.test.util.ReflectionTestUtils.setField(indicator, "probeStubs", stubs);

    when(accessStub.withDeadlineAfter(anyLong(), any(TimeUnit.class))).thenReturn(accessStub);
    when(accessStub.check(any(PlatformProbeRequest.class)))
        .thenThrow(
            new StatusRuntimeException(
                io.grpc.Status.UNAUTHENTICATED.withDescription("bad token")));

    Health health = indicator.health();
    String details = health.getDetails().toString();
    assertFalse(details.contains("token"), "Health details should not contain token");
  }
}
