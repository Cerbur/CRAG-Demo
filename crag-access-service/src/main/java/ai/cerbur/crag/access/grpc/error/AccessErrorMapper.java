package ai.cerbur.crag.access.grpc.error;

import ai.cerbur.crag.access.core.apikey.ApiKeyStateException;
import ai.cerbur.crag.access.core.apikey.ScopeBlockedException;
import ai.cerbur.crag.access.core.apikey.ScopeStateException;
import ai.cerbur.crag.access.core.identity.InvalidCredentialsException;
import ai.cerbur.crag.access.core.identity.UsernameConflictException;
import ai.cerbur.crag.access.core.membership.LastOwnerException;
import ai.cerbur.crag.access.core.membership.MembershipAuthorizationException;
import ai.cerbur.crag.access.core.membership.MembershipNotFoundException;
import ai.cerbur.crag.access.core.membership.MembershipStateException;
import ai.cerbur.crag.access.dao.VersionConflictException;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;

/**
 * 将领域异常映射为稳定的 gRPC {@link StatusRuntimeException}，不泄漏堆栈、SQL、哈希或凭据。
 *
 * <p>凭据/Token/API Key 鉴权失败统一 {@link Status#UNAUTHENTICATED}；Username 冲突 {@link
 * Status#ALREADY_EXISTS}；权限不足 {@link Status#PERMISSION_DENIED}；最后 OWNER、Scope 阻塞与非法状态 {@link
 * Status#FAILED_PRECONDITION}；其余兜底 {@link Status#INTERNAL}。
 */
public final class AccessErrorMapper {

  private AccessErrorMapper() {}

  public static StatusRuntimeException toStatusRuntimeException(RuntimeException e) {
    if (e instanceof StatusRuntimeException sre) {
      return sre;
    }
    if (e instanceof InvalidCredentialsException) {
      return Status.UNAUTHENTICATED.withDescription("invalid credentials").asRuntimeException();
    }
    if (e instanceof UsernameConflictException) {
      return Status.ALREADY_EXISTS.withDescription("username already exists").asRuntimeException();
    }
    if (e instanceof MembershipAuthorizationException) {
      return Status.PERMISSION_DENIED.withDescription("permission denied").asRuntimeException();
    }
    if (e instanceof MembershipNotFoundException) {
      return Status.NOT_FOUND.withDescription("membership not found").asRuntimeException();
    }
    if (e instanceof LastOwnerException
        || e instanceof ScopeBlockedException
        || e instanceof ScopeStateException
        || e instanceof MembershipStateException
        || e instanceof ApiKeyStateException) {
      return Status.FAILED_PRECONDITION.withDescription(safe(e)).asRuntimeException();
    }
    if (e instanceof VersionConflictException) {
      return Status.ABORTED.withDescription("version conflict").asRuntimeException();
    }
    if (e instanceof IllegalArgumentException) {
      return Status.INVALID_ARGUMENT.withDescription(safe(e)).asRuntimeException();
    }
    return Status.INTERNAL.withDescription("internal error").asRuntimeException();
  }

  private static String safe(RuntimeException e) {
    String message = e.getMessage();
    return message == null ? "precondition failed" : message;
  }
}
