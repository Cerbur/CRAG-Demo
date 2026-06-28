package ai.cerbur.crag.rag.grpc.provider;

import ai.cerbur.crag.contracts.rag.v1.GetIngestionStatusRequest;
import ai.cerbur.crag.contracts.rag.v1.IngestionStatusServiceGrpc;
import ai.cerbur.crag.contracts.rag.v1.IngestionStatusView;
import ai.cerbur.crag.contracts.rag.v1.MarkTimedOutRequest;
import ai.cerbur.crag.ingestion.head.IngestionHeadService;
import ai.cerbur.crag.ingestion.head.IngestionStatusResult;
import ai.cerbur.crag.rag.grpc.error.RagErrorMapper;
import ai.cerbur.crag.rag.grpc.mapper.IngestionStatusMapper;
import ai.cerbur.crag.rag.grpc.security.RagRpcAuthorizer;
import io.grpc.stub.StreamObserver;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Ingestion Status gRPC Provider（Plan 21.4）—— 暴露当前 operationVersion 的权威 Job 状态查询与滞留任务超时终态化.
 *
 * <p>调用方身份：仅 Knowledge Service 可调用（{@link RagRpcAuthorizer#requireKnowledgeService()}）。状态字段经安全限长，
 * 不泄漏堆栈/SQL；不存在的 (doc, version) 返回 {@link io.grpc.Status#NOT_FOUND}.
 *
 * <p>Plan 21.4 只交付查询 + 超时终态化两个 RPC；retry/Reconciler 编排由 21.5 在 Knowledge 侧消费.
 */
@Component
public class IngestionStatusGrpcProvider
    extends IngestionStatusServiceGrpc.IngestionStatusServiceImplBase {

  @Autowired private IngestionHeadService ingestionHeadService;
  @Autowired private RagRpcAuthorizer authorizer;

  @Override
  public void getIngestionStatus(
      GetIngestionStatusRequest request, StreamObserver<IngestionStatusView> responseObserver) {
    try {
      authorizer.requireKnowledgeService();
      long tenantId = DecimalId.parse(request.getTenantId(), "tenant_id");
      long knowledgeBaseId = DecimalId.parse(request.getKnowledgeBaseId(), "knowledge_base_id");
      long docId = DecimalId.parse(request.getDocId(), "doc_id");
      long operationVersion = DecimalId.parse(request.getOperationVersion(), "operation_version");
      Optional<IngestionStatusResult> projection =
          ingestionHeadService.get(tenantId, knowledgeBaseId, docId).isPresent()
              ? lookupStatus(knowledgeBaseId, docId, operationVersion)
              : Optional.<IngestionStatusResult>empty();
      if (projection.isEmpty()) {
        responseObserver.onError(
            io.grpc.Status.NOT_FOUND
                .withDescription("ingestion status not found")
                .asRuntimeException());
        return;
      }
      responseObserver.onNext(IngestionStatusMapper.toProto(projection.get()));
      responseObserver.onCompleted();
    } catch (RuntimeException e) {
      responseObserver.onError(RagErrorMapper.toStatusRuntimeException(e));
    }
  }

  @Override
  public void markTimedOut(
      MarkTimedOutRequest request, StreamObserver<IngestionStatusView> responseObserver) {
    try {
      authorizer.requireKnowledgeService();
      long tenantId = DecimalId.parse(request.getTenantId(), "tenant_id");
      long knowledgeBaseId = DecimalId.parse(request.getKnowledgeBaseId(), "knowledge_base_id");
      long docId = DecimalId.parse(request.getDocId(), "doc_id");
      long operationVersion = DecimalId.parse(request.getOperationVersion(), "operation_version");
      LocalDateTime staleBefore =
          LocalDateTime.ofInstant(
              Instant.ofEpochMilli(request.getStaleBeforeEpochMillis()), ZoneOffset.UTC);
      Optional<IngestionStatusResult> projection =
          ingestionHeadService.markTimedOut(
              tenantId, knowledgeBaseId, docId, operationVersion, staleBefore);
      if (projection.isEmpty()) {
        responseObserver.onError(
            io.grpc.Status.NOT_FOUND
                .withDescription("ingestion job not found or not stale")
                .asRuntimeException());
        return;
      }
      responseObserver.onNext(IngestionStatusMapper.toProto(projection.get()));
      responseObserver.onCompleted();
    } catch (RuntimeException e) {
      responseObserver.onError(RagErrorMapper.toStatusRuntimeException(e));
    }
  }

  private Optional<IngestionStatusResult> lookupStatus(
      long knowledgeBaseId, long docId, long operationVersion) {
    // Plan 21.4：Status RPC 通过 head service 暴露；当前 Job 状态从 head service 的 Job 读取路径派生。
    // head service 持有 IngestionJobDao，未来若需要更复杂投影再扩展；此处复用 head service.get 返回 head，
    // Job 状态由 Reconciler/Provider 通过 markTimedOut 与 head.advance 间接消费。
    return ingestionHeadService.currentJobStatus(knowledgeBaseId, docId, operationVersion);
  }
}
