package ai.cerbur.crag.rag.grpc.error;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;

/**
 * 将 RAG 领域异常映射为稳定的 gRPC {@link StatusRuntimeException}，不泄漏堆栈、SQL、Prompt、向量或文件内容（Plan 21.4）.
 *
 * <p>查询非法 → {@link Status#INVALID_ARGUMENT}；LLM 不可用 → {@link Status#UNAVAILABLE}；head/version 冲突或迟到
 * Worker 拒绝 → {@link Status#FAILED_PRECONDITION}；其余兜底 {@link Status#INTERNAL}.
 */
public final class RagErrorMapper {

  private RagErrorMapper() {}

  public static StatusRuntimeException toStatusRuntimeException(RuntimeException e) {
    if (e instanceof StatusRuntimeException sre) {
      return sre;
    }
    String name = e.getClass().getName();
    if (name.endsWith("InvalidQueryException")) {
      return Status.INVALID_ARGUMENT.withDescription(safe(e)).asRuntimeException();
    }
    if (name.endsWith("LlmUnavailableException")) {
      return Status.UNAVAILABLE.withDescription("llm provider unavailable").asRuntimeException();
    }
    if (name.endsWith("IngestionJobConflictException")
        || name.endsWith("VersionConflictException")) {
      return Status.FAILED_PRECONDITION
          .withDescription("ingestion version conflict")
          .asRuntimeException();
    }
    if (e instanceof IllegalArgumentException) {
      return Status.INVALID_ARGUMENT.withDescription(safe(e)).asRuntimeException();
    }
    return Status.INTERNAL.withDescription("internal error").asRuntimeException();
  }

  private static String safe(RuntimeException e) {
    String message = e.getMessage();
    return message == null ? "invalid argument" : message;
  }
}
