package ai.cerbur.crag.knowledge.grpc.provider;

import ai.cerbur.crag.contracts.knowledge.v1.CreateKnowledgeBaseRequest;
import ai.cerbur.crag.contracts.knowledge.v1.GetKnowledgeBaseRequest;
import ai.cerbur.crag.contracts.knowledge.v1.KnowledgeBase;
import ai.cerbur.crag.contracts.knowledge.v1.KnowledgeBaseServiceGrpc;
import ai.cerbur.crag.contracts.knowledge.v1.ListKnowledgeBasesRequest;
import ai.cerbur.crag.contracts.knowledge.v1.ListKnowledgeBasesResponse;
import ai.cerbur.crag.knowledge.core.knowledgebase.KnowledgeBaseService;
import ai.cerbur.crag.knowledge.grpc.error.GrpcErrorMapper;
import ai.cerbur.crag.knowledge.grpc.mapper.KnowledgeBaseMapper;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * KnowledgeBase gRPC provider，只做协议暴露、proto 映射与错误映射；业务由 {@link KnowledgeBaseService} 承担。边界 ID
 * 使用十进制字符串。
 */
@Component
public class KnowledgeBaseGrpcProvider
    extends KnowledgeBaseServiceGrpc.KnowledgeBaseServiceImplBase {

  @Autowired private KnowledgeBaseService knowledgeBaseService;

  @Override
  public void createKnowledgeBase(
      CreateKnowledgeBaseRequest request, StreamObserver<KnowledgeBase> responseObserver) {
    try {
      long tenantId = DecimalId.parse(request.getTenantId(), "tenant_id");
      long createdByUserId = DecimalId.parse(request.getCreatedByUserId(), "created_by_user_id");
      var result = knowledgeBaseService.create(tenantId, request.getName(), createdByUserId);
      responseObserver.onNext(KnowledgeBaseMapper.toProto(result));
      responseObserver.onCompleted();
    } catch (RuntimeException e) {
      responseObserver.onError(GrpcErrorMapper.toStatusRuntimeException(e));
    }
  }

  @Override
  public void getKnowledgeBase(
      GetKnowledgeBaseRequest request, StreamObserver<KnowledgeBase> responseObserver) {
    try {
      long tenantId = DecimalId.parse(request.getTenantId(), "tenant_id");
      long knowledgeBaseId = DecimalId.parse(request.getKnowledgeBaseId(), "knowledge_base_id");
      knowledgeBaseService
          .get(knowledgeBaseId, tenantId)
          .ifPresentOrElse(
              result -> {
                responseObserver.onNext(KnowledgeBaseMapper.toProto(result));
                responseObserver.onCompleted();
              },
              () ->
                  responseObserver.onError(
                      Status.NOT_FOUND
                          .withDescription("knowledge base not found")
                          .asRuntimeException()));
    } catch (RuntimeException e) {
      responseObserver.onError(GrpcErrorMapper.toStatusRuntimeException(e));
    }
  }

  @Override
  public void listKnowledgeBases(
      ListKnowledgeBasesRequest request,
      StreamObserver<ListKnowledgeBasesResponse> responseObserver) {
    try {
      long tenantId = DecimalId.parse(request.getTenantId(), "tenant_id");
      int pageSize = normalizePageSize(request.getPageSize());
      var results = knowledgeBaseService.list(tenantId, PageRequest.ofSize(pageSize));
      var builder = ListKnowledgeBasesResponse.newBuilder();
      results.forEach(result -> builder.addKnowledgeBases(KnowledgeBaseMapper.toProto(result)));
      responseObserver.onNext(builder.build());
      responseObserver.onCompleted();
    } catch (RuntimeException e) {
      responseObserver.onError(GrpcErrorMapper.toStatusRuntimeException(e));
    }
  }

  private static int normalizePageSize(int requested) {
    return requested <= 0 ? 50 : Math.min(requested, 200);
  }
}
