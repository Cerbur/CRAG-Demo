package ai.cerbur.crag.access.grpc.provider;

import ai.cerbur.crag.access.core.membership.AuthorizationRequest;
import ai.cerbur.crag.access.core.membership.MembershipService;
import ai.cerbur.crag.access.grpc.error.AccessErrorMapper;
import ai.cerbur.crag.access.grpc.mapper.MembershipMapper;
import ai.cerbur.crag.access.grpc.security.AccessRpcAuthorizer;
import ai.cerbur.crag.contracts.access.v1.AddMemberByUsernameRequest;
import ai.cerbur.crag.contracts.access.v1.AuthorizationDecision;
import ai.cerbur.crag.contracts.access.v1.AuthorizeTenantActionRequest;
import ai.cerbur.crag.contracts.access.v1.ChangeMemberRoleRequest;
import ai.cerbur.crag.contracts.access.v1.GetMembershipRequest;
import ai.cerbur.crag.contracts.access.v1.ListMembershipsRequest;
import ai.cerbur.crag.contracts.access.v1.ListMembershipsResponse;
import ai.cerbur.crag.contracts.access.v1.Membership;
import ai.cerbur.crag.contracts.access.v1.MembershipServiceGrpc;
import ai.cerbur.crag.contracts.access.v1.RemoveMemberRequest;
import io.grpc.stub.StreamObserver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Membership gRPC provider；Console 管理成员与权限判断。 */
@Component
public class MembershipGrpcProvider extends MembershipServiceGrpc.MembershipServiceImplBase {

  @Autowired private MembershipService membershipService;
  @Autowired private AccessRpcAuthorizer authorizer;

  @Override
  public void authorizeTenantAction(
      AuthorizeTenantActionRequest request,
      StreamObserver<AuthorizationDecision> responseObserver) {
    try {
      authorizer.requireConsole();
      Long resourceOwner =
          request.getResourceOwnerUserId().isEmpty()
              ? null
              : DecimalId.parse(request.getResourceOwnerUserId(), "resource_owner_user_id");
      AuthorizationRequest authRequest =
          new AuthorizationRequest(
              DecimalId.parse(request.getActorUserId(), "actor_user_id"),
              DecimalId.parse(request.getTenantId(), "tenant_id"),
              MembershipMapper.fromProtoAction(request.getAction()),
              resourceOwner);
      responseObserver.onNext(MembershipMapper.toProto(membershipService.authorize(authRequest)));
      responseObserver.onCompleted();
    } catch (RuntimeException e) {
      responseObserver.onError(AccessErrorMapper.toStatusRuntimeException(e));
    }
  }

  @Override
  public void addMemberByUsername(
      AddMemberByUsernameRequest request, StreamObserver<Membership> responseObserver) {
    try {
      authorizer.requireConsole();
      var result =
          membershipService.addByUsername(
              DecimalId.parse(request.getActorUserId(), "actor_user_id"),
              DecimalId.parse(request.getTenantId(), "tenant_id"),
              request.getUsername());
      responseObserver.onNext(MembershipMapper.toProto(result));
      responseObserver.onCompleted();
    } catch (RuntimeException e) {
      responseObserver.onError(AccessErrorMapper.toStatusRuntimeException(e));
    }
  }

  @Override
  public void changeMemberRole(
      ChangeMemberRoleRequest request, StreamObserver<Membership> responseObserver) {
    try {
      authorizer.requireConsole();
      var result =
          membershipService.changeRole(
              DecimalId.parse(request.getActorUserId(), "actor_user_id"),
              DecimalId.parse(request.getTenantId(), "tenant_id"),
              DecimalId.parse(request.getMemberUserId(), "member_user_id"),
              MembershipMapper.fromProtoRole(request.getRole()));
      responseObserver.onNext(MembershipMapper.toProto(result));
      responseObserver.onCompleted();
    } catch (RuntimeException e) {
      responseObserver.onError(AccessErrorMapper.toStatusRuntimeException(e));
    }
  }

  @Override
  public void removeMember(
      RemoveMemberRequest request, StreamObserver<Membership> responseObserver) {
    try {
      authorizer.requireConsole();
      var result =
          membershipService.remove(
              DecimalId.parse(request.getActorUserId(), "actor_user_id"),
              DecimalId.parse(request.getTenantId(), "tenant_id"),
              DecimalId.parse(request.getMemberUserId(), "member_user_id"));
      responseObserver.onNext(MembershipMapper.toProto(result));
      responseObserver.onCompleted();
    } catch (RuntimeException e) {
      responseObserver.onError(AccessErrorMapper.toStatusRuntimeException(e));
    }
  }

  @Override
  public void getMembership(
      GetMembershipRequest request, StreamObserver<Membership> responseObserver) {
    try {
      authorizer.requireConsole();
      var result =
          membershipService.get(
              DecimalId.parse(request.getActorUserId(), "actor_user_id"),
              DecimalId.parse(request.getTenantId(), "tenant_id"),
              DecimalId.parse(request.getMemberUserId(), "member_user_id"));
      responseObserver.onNext(MembershipMapper.toProto(result));
      responseObserver.onCompleted();
    } catch (RuntimeException e) {
      responseObserver.onError(AccessErrorMapper.toStatusRuntimeException(e));
    }
  }

  @Override
  public void listMemberships(
      ListMembershipsRequest request, StreamObserver<ListMembershipsResponse> responseObserver) {
    try {
      authorizer.requireConsole();
      var results =
          membershipService.list(
              DecimalId.parse(request.getActorUserId(), "actor_user_id"),
              DecimalId.parse(request.getTenantId(), "tenant_id"),
              request.getPageSize(),
              request.getPageToken());
      var builder = ListMembershipsResponse.newBuilder();
      results.forEach(r -> builder.addMemberships(MembershipMapper.toProto(r)));
      responseObserver.onNext(builder.build());
      responseObserver.onCompleted();
    } catch (RuntimeException e) {
      responseObserver.onError(AccessErrorMapper.toStatusRuntimeException(e));
    }
  }
}
