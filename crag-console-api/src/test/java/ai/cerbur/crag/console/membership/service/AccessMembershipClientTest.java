package ai.cerbur.crag.console.membership.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.cerbur.crag.console.membership.dto.MemberResponse;
import ai.cerbur.crag.console.membership.dto.MembersListResponse;
import ai.cerbur.crag.console.membership.service.AccessMembershipClient.ConflictException;
import ai.cerbur.crag.console.membership.service.AccessMembershipClient.DownstreamUnavailableException;
import ai.cerbur.crag.console.membership.service.AccessMembershipClient.ForbiddenException;
import ai.cerbur.crag.console.membership.service.AccessMembershipClient.NotFoundException;
import ai.cerbur.crag.contracts.access.v1.AddMemberByUsernameRequest;
import ai.cerbur.crag.contracts.access.v1.ChangeMemberRoleRequest;
import ai.cerbur.crag.contracts.access.v1.GetMembershipRequest;
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
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * AccessMembershipClient 进程内 gRPC 组件测试（plan_21/21.7）。
 *
 * <p>真实跨服务调用由 21.13 Docker 全链路证明；本测试验证 gRPC 装配、gRPC Status →
 * 业务异常映射（403/404/409/503）与字段映射，不表述为端到端兼容证明。
 */
@DisplayName("AccessMembershipClient in-process gRPC")
class AccessMembershipClientTest {

  private Server server;
  private ManagedChannel channel;
  private FakeMembershipService membershipFake;
  private FakeIdentityService identityFake;
  private AccessMembershipClient client;

  @BeforeEach
  void setUp() throws IOException {
    membershipFake = new FakeMembershipService();
    identityFake = new FakeIdentityService();
    String name = InProcessServerBuilder.generateName();
    server =
        InProcessServerBuilder.forName(name)
            .directExecutor()
            .addService(membershipFake)
            .addService(identityFake)
            .build()
            .start();
    channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    client = new AccessMembershipClient(channel, channel, 5000L);
  }

  @AfterEach
  void tearDown() {
    if (channel != null && !channel.isShutdown()) channel.shutdownNow();
    if (server != null && !server.isShutdown()) server.shutdownNow();
  }

  @Test
  @DisplayName("listMembers 透传 actor/tenant/page 参数；返回 MembersListResponse")
  void listMembersMaps() {
    membershipFake.listResponse =
        ListMembershipsResponse.newBuilder()
            .addMemberships(
                membershipProto(
                    2L,
                    1L,
                    "2",
                    MembershipRole.MEMBERSHIP_ROLE_MEMBER,
                    MembershipStatus.MEMBERSHIP_STATUS_ACTIVE))
            .addMemberships(
                membershipProto(
                    3L,
                    1L,
                    "3",
                    MembershipRole.MEMBERSHIP_ROLE_OWNER,
                    MembershipStatus.MEMBERSHIP_STATUS_ACTIVE))
            .setNextPageToken("3")
            .build();

    MembersListResponse resp = client.listMembers(123L, 1L, 50, "");
    assertThat(resp.items()).hasSize(2);
    assertThat(resp.items().get(0).userId()).isEqualTo("2");
    // nickname 来自单用户 GetUserProfile（proto 无批量字段，list 使用 null，记录在 21.7 缺口）
    assertThat(resp.items().get(0).nickname()).isNull();
    assertThat(resp.items().get(0).role()).isEqualTo("MEMBER");
    assertThat(resp.nextPageToken()).isEqualTo("3");
    // actor 只从 principal，不读 body
    assertThat(membershipFake.lastActorUserId).isEqualTo("123");
    assertThat(membershipFake.lastTenantId).isEqualTo("1");
  }

  @Test
  @DisplayName("listMembers PERMISSION_DENIED → ForbiddenException")
  void listMembersForbidden() {
    membershipFake.listStatus = Status.PERMISSION_DENIED;
    assertThatThrownBy(() -> client.listMembers(123L, 1L, 50, ""))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  @DisplayName("listMembers NOT_FOUND → NotFoundException（跨租户不泄漏）")
  void listMembersNotFound() {
    membershipFake.listStatus = Status.NOT_FOUND;
    assertThatThrownBy(() -> client.listMembers(123L, 1L, 50, ""))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  @DisplayName("addMember 返回 MemberResponse，单用户 nickname 通过 GetUserProfile 解析")
  void addMemberResolvesNickname() {
    membershipFake.singleResponse =
        membershipProto(
            2L,
            1L,
            "2",
            MembershipRole.MEMBERSHIP_ROLE_MEMBER,
            MembershipStatus.MEMBERSHIP_STATUS_ACTIVE);
    identityFake.profileResponse =
        UserProfile.newBuilder().setUserId("2").setNickname("bob").build();

    MemberResponse r = client.addMember(123L, 1L, "bob");
    assertThat(r.userId()).isEqualTo("2");
    assertThat(r.nickname()).isEqualTo("bob");
    assertThat(r.role()).isEqualTo("MEMBER");
    assertThat(membershipFake.lastAddUsername).isEqualTo("bob");
  }

  @Test
  @DisplayName("addMember PERMISSION_DENIED → ForbiddenException（MEMBER 无权）")
  void addMemberForbidden() {
    membershipFake.singleStatus = Status.PERMISSION_DENIED;
    assertThatThrownBy(() -> client.addMember(123L, 1L, "bob"))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  @DisplayName("addMember NOT_FOUND → NotFoundException")
  void addMemberNotFound() {
    membershipFake.singleStatus = Status.NOT_FOUND;
    assertThatThrownBy(() -> client.addMember(123L, 1L, "ghost"))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  @DisplayName("changeRole 返回变更后 MemberResponse")
  void changeRoleMaps() {
    membershipFake.singleResponse =
        membershipProto(
            2L,
            1L,
            "2",
            MembershipRole.MEMBERSHIP_ROLE_OWNER,
            MembershipStatus.MEMBERSHIP_STATUS_ACTIVE);
    identityFake.profileResponse =
        UserProfile.newBuilder().setUserId("2").setNickname("bob").build();

    MemberResponse r = client.changeRole(123L, 1L, 2L, "OWNER");
    assertThat(r.role()).isEqualTo("OWNER");
    assertThat(membershipFake.lastChangeRole).isEqualTo(MembershipRole.MEMBERSHIP_ROLE_OWNER);
    assertThat(membershipFake.lastMemberUserId).isEqualTo("2");
  }

  @Test
  @DisplayName("changeRole FAILED_PRECONDITION → ConflictException（最后 OWNER）")
  void changeRoleLastOwnerConflict() {
    membershipFake.singleStatus = Status.FAILED_PRECONDITION;
    assertThatThrownBy(() -> client.changeRole(123L, 1L, 1L, "MEMBER"))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  @DisplayName("changeRole 非法 role → IllegalArgumentException")
  void changeRoleInvalidRole() {
    assertThatThrownBy(() -> client.changeRole(123L, 1L, 2L, "ADMIN"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("removeMember 返回 REMOVED 投影")
  void removeMemberReturnsRemoved() {
    membershipFake.singleResponse =
        membershipProto(
            2L,
            1L,
            "2",
            MembershipRole.MEMBERSHIP_ROLE_MEMBER,
            MembershipStatus.MEMBERSHIP_STATUS_REMOVED);
    identityFake.profileResponse =
        UserProfile.newBuilder().setUserId("2").setNickname("bob").build();

    MemberResponse r = client.removeMember(123L, 1L, 2L);
    assertThat(r.status()).isEqualTo("REMOVED");
  }

  @Test
  @DisplayName("removeMember 最后 OWNER FAILED_PRECONDITION → ConflictException")
  void removeMemberLastOwnerConflict() {
    membershipFake.singleStatus = Status.FAILED_PRECONDITION;
    assertThatThrownBy(() -> client.removeMember(123L, 1L, 1L))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  @DisplayName("UNAVAILABLE → DownstreamUnavailableException")
  void unavailableMapsDownstream() {
    membershipFake.singleStatus = Status.UNAVAILABLE;
    assertThatThrownBy(() -> client.addMember(123L, 1L, "bob"))
        .isInstanceOf(DownstreamUnavailableException.class);
  }

  // ---- helpers / fakes ----

  private static Membership membershipProto(
      long membershipId,
      long tenantId,
      String userId,
      MembershipRole role,
      MembershipStatus status) {
    return Membership.newBuilder()
        .setMembershipId(Long.toString(membershipId))
        .setTenantId(Long.toString(tenantId))
        .setUserId(userId)
        .setRole(role)
        .setStatus(status)
        .setCreatedAtEpochMillis(Instant.parse("2026-06-29T00:00:00Z").toEpochMilli())
        .setUpdatedAtEpochMillis(Instant.parse("2026-06-29T00:00:00Z").toEpochMilli())
        .setVersion(1L)
        .build();
  }

  static class FakeMembershipService extends MembershipServiceGrpc.MembershipServiceImplBase {
    Status listStatus = Status.OK;
    Status singleStatus = Status.OK;
    ListMembershipsResponse listResponse = ListMembershipsResponse.getDefaultInstance();
    Membership singleResponse = Membership.getDefaultInstance();
    String lastActorUserId;
    String lastTenantId;
    String lastMemberUserId;
    String lastAddUsername;
    MembershipRole lastChangeRole;

    @Override
    public void listMemberships(
        ListMembershipsRequest req, StreamObserver<ListMembershipsResponse> resp) {
      lastActorUserId = req.getActorUserId();
      lastTenantId = req.getTenantId();
      if (listStatus != Status.OK) {
        resp.onError(listStatus.asRuntimeException());
        return;
      }
      resp.onNext(listResponse);
      resp.onCompleted();
    }

    @Override
    public void addMemberByUsername(
        AddMemberByUsernameRequest req, StreamObserver<Membership> resp) {
      lastActorUserId = req.getActorUserId();
      lastTenantId = req.getTenantId();
      lastAddUsername = req.getUsername();
      respondSingle(resp);
    }

    @Override
    public void changeMemberRole(ChangeMemberRoleRequest req, StreamObserver<Membership> resp) {
      lastActorUserId = req.getActorUserId();
      lastTenantId = req.getTenantId();
      lastMemberUserId = req.getMemberUserId();
      lastChangeRole = req.getRole();
      respondSingle(resp);
    }

    @Override
    public void removeMember(RemoveMemberRequest req, StreamObserver<Membership> resp) {
      lastActorUserId = req.getActorUserId();
      lastTenantId = req.getTenantId();
      lastMemberUserId = req.getMemberUserId();
      respondSingle(resp);
    }

    @Override
    public void getMembership(GetMembershipRequest req, StreamObserver<Membership> resp) {
      respondSingle(resp);
    }

    private void respondSingle(StreamObserver<Membership> resp) {
      if (singleStatus != Status.OK) {
        resp.onError(singleStatus.asRuntimeException());
        return;
      }
      resp.onNext(singleResponse);
      resp.onCompleted();
    }
  }

  static class FakeIdentityService extends IdentityServiceGrpc.IdentityServiceImplBase {
    UserProfile profileResponse = UserProfile.getDefaultInstance();

    @Override
    public void getUserProfile(GetUserProfileRequest req, StreamObserver<UserProfile> resp) {
      resp.onNext(profileResponse);
      resp.onCompleted();
    }
  }
}
