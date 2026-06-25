package ai.cerbur.crag.knowledge.grpc.error;

import ai.cerbur.crag.knowledge.core.knowledgebase.KnowledgeBaseNotFoundException;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;

/**
 * 将领域异常映射为稳定的 gRPC {@link StatusRuntimeException}，不泄漏内部路径、SQL 或堆栈。
 *
 * <p>校验类非法参数映射为 {@link Status#INVALID_ARGUMENT}；跨租户/不存在映射为 {@link
 * Status#NOT_FOUND}（permission-safe）； 其余映射为 {@link Status#INTERNAL}。
 */
public final class GrpcErrorMapper {

  private GrpcErrorMapper() {}

  public static StatusRuntimeException toStatusRuntimeException(RuntimeException e) {
    if (e instanceof StatusRuntimeException sre) {
      return sre;
    }
    if (e instanceof KnowledgeBaseNotFoundException) {
      return Status.NOT_FOUND.withDescription(e.getMessage()).asRuntimeException();
    }
    if (e instanceof IllegalArgumentException) {
      return Status.INVALID_ARGUMENT.withDescription(safeMessage(e)).asRuntimeException();
    }
    return Status.INTERNAL.withDescription("internal error").asRuntimeException();
  }

  private static String safeMessage(RuntimeException e) {
    String message = e.getMessage();
    return message == null ? "invalid argument" : message;
  }
}
