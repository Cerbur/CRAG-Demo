package ai.cerbur.crag.knowledge.grpc.error;

import ai.cerbur.crag.knowledge.core.ingestion.RetryNotAllowedException;
import ai.cerbur.crag.knowledge.core.knowledgebase.KnowledgeBaseNotFoundException;
import ai.cerbur.crag.knowledge.dao.VersionConflictException;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;

/**
 * 将领域异常映射为稳定的 gRPC {@link StatusRuntimeException}，不泄漏内部路径、SQL 或堆栈。
 *
 * <p>校验类非法参数映射为 {@link Status#INVALID_ARGUMENT}；跨租户/不存在映射为 {@link
 * Status#NOT_FOUND}（permission-safe）；retry 不允许（非 FAILED、不可重试分类、已达上限）映射为 {@link
 * Status#FAILED_PRECONDITION}；retry 并发 CAS 冲突映射为 {@link Status#ALREADY_EXISTS}（已被并发 retry 推进）；
 * 其余映射为 {@link Status#INTERNAL}。
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
    if (e instanceof RetryNotAllowedException) {
      return Status.FAILED_PRECONDITION.withDescription(safeMessage(e)).asRuntimeException();
    }
    if (e instanceof VersionConflictException) {
      return Status.ALREADY_EXISTS.withDescription("concurrent retry").asRuntimeException();
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
