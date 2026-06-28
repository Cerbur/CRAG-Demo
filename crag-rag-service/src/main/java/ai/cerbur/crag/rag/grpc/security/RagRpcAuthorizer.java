package ai.cerbur.crag.rag.grpc.security;

import ai.cerbur.crag.grpc.runtime.identity.GrpcCallerContext;
import io.grpc.Status;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 按 gRPC 调用方收紧 RAG RPC（Plan 21.4）.
 *
 * <p>Open API 可调用 {@code QueryService.Query}；Knowledge 可调用 {@code
 * IngestionStatusService}（Reconciler/Console 投影 通过 Knowledge 中转，21.5）。Console 直接的 Document 状态展示在
 * 21.8 通过 Knowledge 投影实现，不直接调用 RAG Status. 其他 caller 一律 {@link Status#PERMISSION_DENIED}，避免内部 Probe
 * 或未知服务读取 RAG 查询/状态.
 *
 * <p>调用方身份来自 gRPC Service Identity（{@link GrpcCallerContext}）.
 */
@Component
public class RagRpcAuthorizer {

  static final String OPEN_API = "open-api";
  static final String KNOWLEDGE_SERVICE = "knowledge-service";

  @Autowired private GrpcCallerContext callerContext;

  /** 要求调用方为 Open API（Query）. */
  public void requireOpenApi() {
    requireCaller(OPEN_API);
  }

  /** 要求调用方为 Knowledge Service（IngestionStatus）. */
  public void requireKnowledgeService() {
    requireCaller(KNOWLEDGE_SERVICE);
  }

  private void requireCaller(String expected) {
    String caller = callerContext.requireIdentity().serviceName();
    if (!expected.equals(caller)) {
      throw Status.PERMISSION_DENIED.withDescription("caller not allowed").asRuntimeException();
    }
  }
}
