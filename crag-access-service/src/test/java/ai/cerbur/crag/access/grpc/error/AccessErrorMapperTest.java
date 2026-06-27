package ai.cerbur.crag.access.grpc.error;

import static org.junit.jupiter.api.Assertions.*;

import ai.cerbur.crag.access.core.apikey.ApiKeyStateException;
import ai.cerbur.crag.access.core.apikey.ScopeBlockedException;
import ai.cerbur.crag.access.core.identity.InvalidCredentialsException;
import ai.cerbur.crag.access.core.identity.UsernameConflictException;
import ai.cerbur.crag.access.core.membership.LastOwnerException;
import ai.cerbur.crag.access.core.membership.MembershipAuthorizationException;
import ai.cerbur.crag.access.core.membership.MembershipNotFoundException;
import ai.cerbur.crag.access.dao.VersionConflictException;
import io.grpc.Status;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** AccessErrorMapper 纯单元测试：领域异常到稳定 gRPC Status 的分类。 */
class AccessErrorMapperTest {

  @Test
  @DisplayName("凭据失败映射为 UNAUTHENTICATED")
  void invalidCredentialsUnauthenticated() {
    assertEquals(
        Status.Code.UNAUTHENTICATED,
        AccessErrorMapper.toStatusRuntimeException(new InvalidCredentialsException())
            .getStatus()
            .getCode());
  }

  @Test
  @DisplayName("Username 冲突映射为 ALREADY_EXISTS")
  void usernameConflictAlreadyExists() {
    assertEquals(
        Status.Code.ALREADY_EXISTS,
        AccessErrorMapper.toStatusRuntimeException(new UsernameConflictException())
            .getStatus()
            .getCode());
  }

  @Test
  @DisplayName("权限不足映射为 PERMISSION_DENIED")
  void authorizationDenied() {
    assertEquals(
        Status.Code.PERMISSION_DENIED,
        AccessErrorMapper.toStatusRuntimeException(new MembershipAuthorizationException())
            .getStatus()
            .getCode());
  }

  @Test
  @DisplayName("成员未找到映射为 NOT_FOUND")
  void membershipNotFound() {
    assertEquals(
        Status.Code.NOT_FOUND,
        AccessErrorMapper.toStatusRuntimeException(new MembershipNotFoundException())
            .getStatus()
            .getCode());
  }

  @Test
  @DisplayName("最后 OWNER、Scope 阻塞与非法状态映射为 FAILED_PRECONDITION")
  void preconditions() {
    assertEquals(
        Status.Code.FAILED_PRECONDITION,
        AccessErrorMapper.toStatusRuntimeException(new LastOwnerException()).getStatus().getCode());
    assertEquals(
        Status.Code.FAILED_PRECONDITION,
        AccessErrorMapper.toStatusRuntimeException(new ScopeBlockedException())
            .getStatus()
            .getCode());
    assertEquals(
        Status.Code.FAILED_PRECONDITION,
        AccessErrorMapper.toStatusRuntimeException(new ApiKeyStateException("x"))
            .getStatus()
            .getCode());
  }

  @Test
  @DisplayName("版本冲突映射为 ABORTED，非法参数映射为 INVALID_ARGUMENT，兜底 INTERNAL")
  void conflictArgumentInternal() {
    assertEquals(
        Status.Code.ABORTED,
        AccessErrorMapper.toStatusRuntimeException(new VersionConflictException("c"))
            .getStatus()
            .getCode());
    assertEquals(
        Status.Code.INVALID_ARGUMENT,
        AccessErrorMapper.toStatusRuntimeException(new IllegalArgumentException("bad"))
            .getStatus()
            .getCode());
    assertEquals(
        Status.Code.INTERNAL,
        AccessErrorMapper.toStatusRuntimeException(new RuntimeException("boom"))
            .getStatus()
            .getCode());
  }
}
