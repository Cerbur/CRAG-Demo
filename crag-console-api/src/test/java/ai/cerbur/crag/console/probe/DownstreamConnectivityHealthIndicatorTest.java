package ai.cerbur.crag.console.probe;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import ai.cerbur.crag.contracts.platform.v1.PlatformProbeRequest;
import ai.cerbur.crag.contracts.platform.v1.PlatformProbeResponse;
import ai.cerbur.crag.contracts.platform.v1.PlatformProbeServiceGrpc;
import ai.cerbur.crag.grpc.runtime.client.GrpcClientProperties;
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

    GrpcClientProperties clientProps = new GrpcClientProperties();
    clientProps.setCallerService("console-api");

    indicator = new DownstreamConnectivityHealthIndicator();
    org.springframework.test.util.ReflectionTestUtils.setField(indicator, "executor", executor);
    org.springframework.test.util.ReflectionTestUtils.setField(
        indicator, "clientProperties", clientProps);
  }

  @Test
  @DisplayName("全部 Probe 成功时 readiness 为 UP")
  void allProbesSuccess() {
    Map<String, PlatformProbeServiceGrpc.PlatformProbeServiceBlockingStub> stubs =
        new LinkedHashMap<>();
    stubs.put("access-service", accessStub);
    stubs.put("rag-service", ragStub);
    org.springframework.test.util.ReflectionTestUtils.setField(indicator, "probeStubs", stubs);

    PlatformProbeResponse accessResp =
        PlatformProbeResponse.newBuilder()
            .setServiceName("access-service")
            .setCallerService("console-api")
            .build();
    PlatformProbeResponse ragResp =
        PlatformProbeResponse.newBuilder()
            .setServiceName("rag-service")
            .setCallerService("console-api")
            .build();
    when(accessStub.withDeadlineAfter(anyLong(), any(TimeUnit.class))).thenReturn(accessStub);
    when(accessStub.check(any(PlatformProbeRequest.class))).thenReturn(accessResp);
    when(ragStub.withDeadlineAfter(anyLong(), any(TimeUnit.class))).thenReturn(ragStub);
    when(ragStub.check(any(PlatformProbeRequest.class))).thenReturn(ragResp);

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

    PlatformProbeResponse accessResp =
        PlatformProbeResponse.newBuilder()
            .setServiceName("access-service")
            .setCallerService("console-api")
            .build();
    when(accessStub.withDeadlineAfter(anyLong(), any(TimeUnit.class))).thenReturn(accessStub);
    when(accessStub.check(any(PlatformProbeRequest.class))).thenReturn(accessResp);
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

  @Test
  @DisplayName("错误 serviceName 使目标 readiness 为 DOWN")
  void wrongServiceName_marksDown() {
    Map<String, PlatformProbeServiceGrpc.PlatformProbeServiceBlockingStub> stubs =
        new LinkedHashMap<>();
    stubs.put("access-service", accessStub);
    org.springframework.test.util.ReflectionTestUtils.setField(indicator, "probeStubs", stubs);

    PlatformProbeResponse wrongResp =
        PlatformProbeResponse.newBuilder()
            .setServiceName("knowledge-service")
            .setCallerService("console-api")
            .build();
    when(accessStub.withDeadlineAfter(anyLong(), any(TimeUnit.class))).thenReturn(accessStub);
    when(accessStub.check(any(PlatformProbeRequest.class))).thenReturn(wrongResp);

    Health health = indicator.health();
    assertEquals("DOWN", health.getDetails().get("access-service"));
  }

  @Test
  @DisplayName("错误 callerService 使目标 readiness 为 DOWN")
  void wrongCallerService_marksDown() {
    Map<String, PlatformProbeServiceGrpc.PlatformProbeServiceBlockingStub> stubs =
        new LinkedHashMap<>();
    stubs.put("access-service", accessStub);
    org.springframework.test.util.ReflectionTestUtils.setField(indicator, "probeStubs", stubs);

    PlatformProbeResponse wrongResp =
        PlatformProbeResponse.newBuilder()
            .setServiceName("access-service")
            .setCallerService("open-api")
            .build();
    when(accessStub.withDeadlineAfter(anyLong(), any(TimeUnit.class))).thenReturn(accessStub);
    when(accessStub.check(any(PlatformProbeRequest.class))).thenReturn(wrongResp);

    Health health = indicator.health();
    assertEquals("DOWN", health.getDetails().get("access-service"));
  }

  @Test
  @DisplayName("总预算到期后未完成 Future 被取消")
  void totalBudgetExpired_cancelsFutures() {
    Map<String, PlatformProbeServiceGrpc.PlatformProbeServiceBlockingStub> stubs =
        new LinkedHashMap<>();
    stubs.put("access-service", accessStub);
    stubs.put("knowledge-service", ragStub);
    org.springframework.test.util.ReflectionTestUtils.setField(indicator, "probeStubs", stubs);

    PlatformProbeResponse okResp =
        PlatformProbeResponse.newBuilder()
            .setServiceName("access-service")
            .setCallerService("console-api")
            .build();
    when(accessStub.withDeadlineAfter(anyLong(), any(TimeUnit.class))).thenReturn(accessStub);
    when(accessStub.check(any(PlatformProbeRequest.class))).thenReturn(okResp);

    when(ragStub.withDeadlineAfter(anyLong(), any(TimeUnit.class))).thenReturn(ragStub);
    when(ragStub.check(any(PlatformProbeRequest.class)))
        .thenAnswer(
            invocation -> {
              Thread.sleep(10_000);
              return okResp;
            });

    Health health = indicator.health();
    assertEquals("UP", health.getDetails().get("access-service"));
    assertEquals("TIMEOUT", health.getDetails().get("knowledge-service"));
  }
}
