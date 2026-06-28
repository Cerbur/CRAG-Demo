package ai.cerbur.crag.knowledge.reconcile;

import ai.cerbur.crag.contracts.rag.v1.GetIngestionStatusRequest;
import ai.cerbur.crag.contracts.rag.v1.IngestionStatusServiceGrpc;
import ai.cerbur.crag.contracts.rag.v1.IngestionStatusView;
import ai.cerbur.crag.contracts.rag.v1.MarkTimedOutRequest;
import ai.cerbur.crag.grpc.runtime.client.GrpcChannelFactory;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 通过 gRPC 调用 RAG {@code IngestionStatusService} 的生产实现（plan_21/21.5）。
 *
 * <p>Reconciler 在数据库事务外通过本客户端查询 RAG 权威 Job 状态，并按需终态化滞留 PROCESSING 任务。调用方身份与 service token 由 {@link
 * GrpcChannelFactory} 的拦截器自动附加（{@code crag.grpc.client.caller-service=knowledge-service} / {@code
 * crag.grpc.client.token}）。
 *
 * <p>本类只负责 gRPC 调用与 {@link IngestionStatusView} → {@link RagIngestionStatus} 映射；业务决策（修复/重试/超时）由
 * {@link IngestionReconcileService} 处理。gRPC 错误（含 UNAVAILABLE / DEADLINE_EXCEEDED）向上传播，由调用方降级为
 * RAG_UNAVAILABLE。
 */
@Component
public class GrpcRagIngestionStatusClient implements RagIngestionStatusClient {

  private static final Logger log = LoggerFactory.getLogger(GrpcRagIngestionStatusClient.class);

  private final GrpcChannelFactory channelFactory;
  private final String ragTarget;
  private final long deadlineMillis;

  private volatile ManagedChannel channel;
  private volatile IngestionStatusServiceGrpc.IngestionStatusServiceBlockingStub stub;

  @Autowired
  public GrpcRagIngestionStatusClient(
      GrpcChannelFactory channelFactory,
      @Value("${crag.knowledge.reconciler.rag-target:rag-service:9093}") String ragTarget,
      @Value("${crag.grpc.client.max-deadline-millis:10000}") long deadlineMillis) {
    this.channelFactory = channelFactory;
    this.ragTarget = ragTarget;
    this.deadlineMillis = deadlineMillis;
  }

  /** 测试可注入已构造 channel，跳过 channelFactory. */
  GrpcRagIngestionStatusClient(ManagedChannel channel, long deadlineMillis) {
    this.channelFactory = null;
    this.ragTarget = null;
    this.deadlineMillis = deadlineMillis;
    this.channel = channel;
    this.stub = IngestionStatusServiceGrpc.newBlockingStub(channel);
  }

  /** 单元测试可注入已构造 blocking stub，跳过 channel 与 deadline 配置. */
  GrpcRagIngestionStatusClient(IngestionStatusServiceGrpc.IngestionStatusServiceBlockingStub stub) {
    this.channelFactory = null;
    this.ragTarget = null;
    this.deadlineMillis = 0L;
    this.stub = stub;
  }

  @Override
  public Optional<RagIngestionStatus> getStatus(
      long tenantId, long knowledgeBaseId, long docId, long operationVersion) {
    try {
      IngestionStatusView view =
          stub()
              .getIngestionStatus(
                  GetIngestionStatusRequest.newBuilder()
                      .setTenantId(Long.toString(tenantId))
                      .setKnowledgeBaseId(Long.toString(knowledgeBaseId))
                      .setDocId(Long.toString(docId))
                      .setOperationVersion(Long.toString(operationVersion))
                      .build());
      return Optional.of(toResult(view));
    } catch (StatusRuntimeException e) {
      if (e.getStatus().getCode() == io.grpc.Status.Code.NOT_FOUND) {
        return Optional.empty();
      }
      log.warn(
          "RAG getStatus RPC failed — docId={} op={} code={} reason={}",
          docId,
          operationVersion,
          e.getStatus().getCode(),
          e.getStatus().getDescription());
      throw e;
    }
  }

  @Override
  public Optional<RagIngestionStatus> markTimedOut(
      long tenantId, long knowledgeBaseId, long docId, long operationVersion, Instant staleBefore) {
    try {
      IngestionStatusView view =
          stub()
              .markTimedOut(
                  MarkTimedOutRequest.newBuilder()
                      .setTenantId(Long.toString(tenantId))
                      .setKnowledgeBaseId(Long.toString(knowledgeBaseId))
                      .setDocId(Long.toString(docId))
                      .setOperationVersion(Long.toString(operationVersion))
                      .setStaleBeforeEpochMillis(staleBefore.toEpochMilli())
                      .build());
      return Optional.of(toResult(view));
    } catch (StatusRuntimeException e) {
      if (e.getStatus().getCode() == io.grpc.Status.Code.NOT_FOUND) {
        return Optional.empty();
      }
      log.warn(
          "RAG markTimedOut RPC failed — docId={} op={} code={} reason={}",
          docId,
          operationVersion,
          e.getStatus().getCode(),
          e.getStatus().getDescription());
      throw e;
    }
  }

  private IngestionStatusServiceGrpc.IngestionStatusServiceBlockingStub stub() {
    if (stub == null) {
      synchronized (this) {
        if (stub == null) {
          channel = channelFactory.create("rag-service", ragTarget, true);
          stub = IngestionStatusServiceGrpc.newBlockingStub(channel);
        }
      }
    }
    if (deadlineMillis > 0) {
      return stub.withDeadlineAfter(deadlineMillis, TimeUnit.MILLISECONDS);
    }
    return stub;
  }

  private static RagIngestionStatus toResult(IngestionStatusView view) {
    Long startedAt = view.getStartedAtEpochMillis() == 0L ? null : view.getStartedAtEpochMillis();
    Long completedAt =
        view.getCompletedAtEpochMillis() == 0L ? null : view.getCompletedAtEpochMillis();
    long opVersion = parseLong(view.getOperationVersion());
    long jobId = parseLong(view.getJobId());
    return new RagIngestionStatus(
        opVersion,
        view.getStatus(),
        view.getAttempt(),
        jobId,
        view.getFailureCategory().isEmpty() ? null : view.getFailureCategory(),
        view.getFailureMessage().isEmpty() ? null : view.getFailureMessage(),
        startedAt,
        completedAt);
  }

  private static long parseLong(String value) {
    if (value == null || value.isBlank()) {
      return 0L;
    }
    try {
      return Long.parseLong(value.trim());
    } catch (NumberFormatException e) {
      return 0L;
    }
  }
}
