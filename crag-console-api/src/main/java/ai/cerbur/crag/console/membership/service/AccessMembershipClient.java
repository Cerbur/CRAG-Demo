package ai.cerbur.crag.console.membership.service;

import ai.cerbur.crag.console.membership.dto.MemberResponse;
import ai.cerbur.crag.console.membership.dto.MembersListResponse;
import ai.cerbur.crag.contracts.access.v1.AddMemberByUsernameRequest;
import ai.cerbur.crag.contracts.access.v1.ChangeMemberRoleRequest;
import ai.cerbur.crag.contracts.access.v1.GetUserProfileRequest;
import ai.cerbur.crag.contracts.access.v1.IdentityServiceGrpc;
import ai.cerbur.crag.contracts.access.v1.ListMembershipsRequest;
import ai.cerbur.crag.contracts.access.v1.ListMembershipsResponse;
import ai.cerbur.crag.contracts.access.v1.Membership;
import ai.cerbur.crag.contracts.access.v1.MembershipRole;
import ai.cerbur.crag.contracts.access.v1.MembershipServiceGrpc;
import ai.cerbur.crag.contracts.access.v1.MembershipStatus;
import ai.cerbur.crag.contracts.access.v1.RemoveMemberRequest;
import ai.cerbur.crag.contracts.access.v1.UserProfile;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Access Membership gRPC 适配器（plan_21/21.7）。
 *
 * <p>封装 list/add/change-role/remove 的 gRPC 调用，将稳定 gRPC Status 映射为 Console 业务异常。
 *
 * <p>只接受 principal {@code userId}（来自 ConsolePrincipal），从不读取请求体中的 actorUserId，防越权。
 *
 * <p>单成员命令（add/change-role/remove）通过 {@link IdentityServiceGrpc#getUserProfile} 解析 nickname；list
 * 操作读取 Access 批量补齐的 proto {@link Membership#getNickname()}（plan_21/21.7 修复）。非幂等变更命令不自动重试。
 */
@Component
public class AccessMembershipClient {

  private static final Logger log = LoggerFactory.getLogger(AccessMembershipClient.class);

  private final MembershipServiceGrpc.MembershipServiceBlockingStub membershipStub;
  private final IdentityServiceGrpc.IdentityServiceBlockingStub identityStub;
  private final long deadlineMillis;

  /**
   * 构造时注入 Access channel Bean（与 AccessIdentityClient 共用 {@code consoleAccessChannel}）。
   *
   * <p>测试可注入进程内 channel；Spring 注入由 {@code ConsoleGrpcClientConfiguration} 创建的 Bean。两个 channel 参数
   * 在测试中可传入同一进程内 channel；生产环境通过 {@code @Qualifier} 绑定同一 Access channel Bean。
   */
  @Autowired
  public AccessMembershipClient(
      @Qualifier("consoleAccessChannel") ManagedChannel membershipChannel,
      @Qualifier("consoleAccessChannel") ManagedChannel identityChannel,
      @Value("${crag.grpc.client.max-deadline-millis:10000}") long deadlineMillis) {
    this.membershipStub = MembershipServiceGrpc.newBlockingStub(membershipChannel);
    this.identityStub = IdentityServiceGrpc.newBlockingStub(identityChannel);
    this.deadlineMillis = deadlineMillis;
  }

  /** 列出 Tenant 成员。调用方须为有效成员；跨租户 NOT_FOUND 不泄漏。list nickname 来自 Access 批量补齐的 proto 字段。 */
  public MembersListResponse listMembers(
      long actorUserId, long tenantId, int pageSize, String pageToken) {
    try {
      ListMembershipsResponse resp =
          membershipStubWithDeadline()
              .listMemberships(
                  ListMembershipsRequest.newBuilder()
                      .setActorUserId(Long.toString(actorUserId))
                      .setTenantId(Long.toString(tenantId))
                      .setPageSize(pageSize)
                      .setPageToken(pageToken == null ? "" : pageToken)
                      .build());
      List<MemberResponse> items = new ArrayList<>();
      for (Membership m : resp.getMembershipsList()) {
        // nickname 来自 Access list 批量补齐的 proto Membership.nickname（plan_21/21.7 修复）。
        items.add(toMemberResponse(m, m.getNickname()));
      }
      String next = resp.getNextPageToken();
      return new MembersListResponse(items, next == null || next.isEmpty() ? null : next);
    } catch (StatusRuntimeException e) {
      throw mapMembership(e);
    }
  }

  /** OWNER 按 Username 添加已注册用户；返回含 nickname 的 MemberResponse。 */
  public MemberResponse addMember(long actorUserId, long tenantId, String username) {
    try {
      Membership m =
          membershipStubWithDeadline()
              .addMemberByUsername(
                  AddMemberByUsernameRequest.newBuilder()
                      .setActorUserId(Long.toString(actorUserId))
                      .setTenantId(Long.toString(tenantId))
                      .setUsername(username)
                      .build());
      return toMemberResponse(m, resolveNickname(m.getUserId()));
    } catch (StatusRuntimeException e) {
      throw mapMembership(e);
    }
  }

  /** OWNER 调整成员角色；返回含 nickname 的 MemberResponse。非法 role 抛 IllegalArgumentException。 */
  public MemberResponse changeRole(
      long actorUserId, long tenantId, long memberUserId, String role) {
    MembershipRole protoRole = parseRole(role);
    try {
      Membership m =
          membershipStubWithDeadline()
              .changeMemberRole(
                  ChangeMemberRoleRequest.newBuilder()
                      .setActorUserId(Long.toString(actorUserId))
                      .setTenantId(Long.toString(tenantId))
                      .setMemberUserId(Long.toString(memberUserId))
                      .setRole(protoRole)
                      .build());
      return toMemberResponse(m, resolveNickname(m.getUserId()));
    } catch (StatusRuntimeException e) {
      throw mapMembership(e);
    }
  }

  /** OWNER 移除成员；返回 REMOVED 投影的 MemberResponse（HTTP 200）。最后 OWNER 抛 ConflictException。 */
  public MemberResponse removeMember(long actorUserId, long tenantId, long memberUserId) {
    try {
      Membership m =
          membershipStubWithDeadline()
              .removeMember(
                  RemoveMemberRequest.newBuilder()
                      .setActorUserId(Long.toString(actorUserId))
                      .setTenantId(Long.toString(tenantId))
                      .setMemberUserId(Long.toString(memberUserId))
                      .build());
      return toMemberResponse(m, resolveNickname(m.getUserId()));
    } catch (StatusRuntimeException e) {
      throw mapMembership(e);
    }
  }

  // ---- helpers ----

  private MembershipServiceGrpc.MembershipServiceBlockingStub membershipStubWithDeadline() {
    if (deadlineMillis > 0) {
      return membershipStub.withDeadlineAfter(deadlineMillis, TimeUnit.MILLISECONDS);
    }
    return membershipStub;
  }

  private IdentityServiceGrpc.IdentityServiceBlockingStub identityStubWithDeadline() {
    if (deadlineMillis > 0) {
      return identityStub.withDeadlineAfter(deadlineMillis, TimeUnit.MILLISECONDS);
    }
    return identityStub;
  }

  private String resolveNickname(String userId) {
    try {
      UserProfile p =
          identityStubWithDeadline()
              .getUserProfile(GetUserProfileRequest.newBuilder().setUserId(userId).build());
      return p.getNickname();
    } catch (StatusRuntimeException e) {
      // nickname 解析失败不阻断成员命令；返回 null，业务命令已经成功
      log.debug(
          "Membership 命令返回后解析 nickname 失败 — userId={} code={}", userId, e.getStatus().getCode());
      return null;
    }
  }

  private static MemberResponse toMemberResponse(Membership m, String nickname) {
    return new MemberResponse(
        m.getUserId(),
        nickname,
        roleToString(m.getRole()),
        statusToString(m.getStatus()),
        epochMillisToInstant(m.getCreatedAtEpochMillis()),
        epochMillisToInstant(m.getUpdatedAtEpochMillis()));
  }

  private static String roleToString(MembershipRole role) {
    return switch (role) {
      case MEMBERSHIP_ROLE_OWNER -> "OWNER";
      case MEMBERSHIP_ROLE_MEMBER -> "MEMBER";
      case MEMBERSHIP_ROLE_UNSPECIFIED, UNRECOGNIZED -> "MEMBER";
    };
  }

  private static String statusToString(MembershipStatus status) {
    return switch (status) {
      case MEMBERSHIP_STATUS_ACTIVE -> "ACTIVE";
      case MEMBERSHIP_STATUS_REMOVED -> "REMOVED";
      case MEMBERSHIP_STATUS_UNSPECIFIED, UNRECOGNIZED -> "ACTIVE";
    };
  }

  private static MembershipRole parseRole(String role) {
    if (role == null) {
      throw new IllegalArgumentException("role must not be null");
    }
    return switch (role.trim().toUpperCase()) {
      case "OWNER" -> MembershipRole.MEMBERSHIP_ROLE_OWNER;
      case "MEMBER" -> MembershipRole.MEMBERSHIP_ROLE_MEMBER;
      default -> throw new IllegalArgumentException("role must be OWNER or MEMBER: " + role);
    };
  }

  private static Instant epochMillisToInstant(long millis) {
    return millis <= 0 ? null : Instant.ofEpochMilli(millis);
  }

  private static RuntimeException mapMembership(StatusRuntimeException e) {
    Status.Code code = e.getStatus().getCode();
    if (code == Status.Code.PERMISSION_DENIED) {
      return new ForbiddenException();
    }
    if (code == Status.Code.NOT_FOUND) {
      return new NotFoundException();
    }
    if (code == Status.Code.FAILED_PRECONDITION
        || code == Status.Code.ALREADY_EXISTS
        || code == Status.Code.ABORTED) {
      return new ConflictException();
    }
    if (code == Status.Code.DEADLINE_EXCEEDED) {
      return new DownstreamTimeoutException();
    }
    if (code == Status.Code.INVALID_ARGUMENT) {
      return new IllegalArgumentException("invalid membership argument");
    }
    log.warn("Access Membership 下游调用失败 — code={} desc={}", code, e.getStatus().getDescription());
    return new DownstreamUnavailableException();
  }

  /** MEMBER 无权管理或 actor 非有效成员 → 403 FORBIDDEN。 */
  public static class ForbiddenException extends RuntimeException {
    public ForbiddenException() {
      super("forbidden");
    }
  }

  /** 跨租户或成员/用户不存在 → 404 NOT_FOUND，不泄漏存在性。 */
  public static class NotFoundException extends RuntimeException {
    public NotFoundException() {
      super("not found");
    }
  }

  /** 最后 OWNER 保护或状态冲突 → 409 CONFLICT。 */
  public static class ConflictException extends RuntimeException {
    public ConflictException() {
      super("conflict");
    }
  }

  /** 下游 Access 不可用 → 503 DOWNSTREAM_UNAVAILABLE。 */
  public static class DownstreamUnavailableException extends RuntimeException {
    public DownstreamUnavailableException() {
      super("downstream unavailable");
    }
  }

  /** 下游 Access 超时 → 504 DOWNSTREAM_TIMEOUT。 */
  public static class DownstreamTimeoutException extends RuntimeException {
    public DownstreamTimeoutException() {
      super("downstream timeout");
    }
  }
}
