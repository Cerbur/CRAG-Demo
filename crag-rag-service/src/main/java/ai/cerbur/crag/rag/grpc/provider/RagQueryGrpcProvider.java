package ai.cerbur.crag.rag.grpc.provider;

import ai.cerbur.crag.contracts.rag.v1.QueryRequest;
import ai.cerbur.crag.contracts.rag.v1.QueryResponse;
import ai.cerbur.crag.contracts.rag.v1.QueryServiceGrpc;
import ai.cerbur.crag.query.api.UserQueryOutcome;
import ai.cerbur.crag.query.api.UserQueryService;
import ai.cerbur.crag.rag.grpc.error.RagErrorMapper;
import ai.cerbur.crag.rag.grpc.mapper.RagQueryMapper;
import ai.cerbur.crag.rag.grpc.security.RagRpcAuthorizer;
import io.grpc.stub.StreamObserver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * RAG Query gRPC Provider（Plan 21.4）—— 只做协议暴露、调用方授权、proto 映射与错误转换；业务由 {@link UserQueryService} 承担.
 *
 * <p>调用方身份校验：仅 Open API 可调用 Query（{@link RagRpcAuthorizer#requireOpenApi()}）。Citation 只暴露 {@code
 * reference / documentId / excerpt}，excerpt 在 {@link RagQueryMapper} 防御截断到 500 个 Unicode 字符.
 */
@Component
public class RagQueryGrpcProvider extends QueryServiceGrpc.QueryServiceImplBase {

  @Autowired private UserQueryService userQueryService;
  @Autowired private RagRpcAuthorizer authorizer;

  @Override
  public void query(QueryRequest request, StreamObserver<QueryResponse> responseObserver) {
    try {
      authorizer.requireOpenApi();
      long knowledgeBaseId = DecimalId.parse(request.getKnowledgeBaseId(), "knowledge_base_id");
      UserQueryOutcome outcome =
          userQueryService.answerWithEvidence(knowledgeBaseId, request.getQuestion());
      QueryResponse response = RagQueryMapper.toProto(outcome.result(), outcome.evidence());
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (RuntimeException e) {
      responseObserver.onError(RagErrorMapper.toStatusRuntimeException(e));
    }
  }
}
