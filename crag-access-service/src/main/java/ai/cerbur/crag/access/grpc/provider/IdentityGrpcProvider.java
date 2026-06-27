package ai.cerbur.crag.access.grpc.provider;

import ai.cerbur.crag.access.core.identity.RegisterIdentityCommand;
import ai.cerbur.crag.access.core.session.AuthenticationService;
import ai.cerbur.crag.access.core.session.JwtIssuer;
import ai.cerbur.crag.access.grpc.error.AccessErrorMapper;
import ai.cerbur.crag.access.grpc.mapper.IdentityMapper;
import ai.cerbur.crag.access.grpc.security.AccessRpcAuthorizer;
import ai.cerbur.crag.contracts.access.v1.AuthenticationResponse;
import ai.cerbur.crag.contracts.access.v1.GetJwtVerificationKeysRequest;
import ai.cerbur.crag.contracts.access.v1.IdentityServiceGrpc;
import ai.cerbur.crag.contracts.access.v1.JwtVerificationKeySet;
import ai.cerbur.crag.contracts.access.v1.LoginRequest;
import ai.cerbur.crag.contracts.access.v1.LogoutRequest;
import ai.cerbur.crag.contracts.access.v1.LogoutResponse;
import ai.cerbur.crag.contracts.access.v1.RefreshRequest;
import ai.cerbur.crag.contracts.access.v1.RegisterRequest;
import io.grpc.stub.StreamObserver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Identity gRPC provider：只做协议暴露、调用方授权、proto 映射与错误转换；业务由 Core 承担。Console 管理身份/会话；Console 与 Open API
 * 均可读取 JWT 公钥。
 */
@Component
public class IdentityGrpcProvider extends IdentityServiceGrpc.IdentityServiceImplBase {

  @Autowired private AuthenticationService authenticationService;
  @Autowired private JwtIssuer jwtIssuer;
  @Autowired private AccessRpcAuthorizer authorizer;

  @Override
  public void register(
      RegisterRequest request, StreamObserver<AuthenticationResponse> responseObserver) {
    try {
      authorizer.requireConsole();
      var result =
          authenticationService.register(
              new RegisterIdentityCommand(
                  request.getNickname(),
                  request.getUsername(),
                  request.getPassword().toCharArray()));
      responseObserver.onNext(IdentityMapper.toProto(result));
      responseObserver.onCompleted();
    } catch (RuntimeException e) {
      responseObserver.onError(AccessErrorMapper.toStatusRuntimeException(e));
    }
  }

  @Override
  public void login(LoginRequest request, StreamObserver<AuthenticationResponse> responseObserver) {
    try {
      authorizer.requireConsole();
      var result =
          authenticationService.login(request.getUsername(), request.getPassword().toCharArray());
      responseObserver.onNext(IdentityMapper.toProto(result));
      responseObserver.onCompleted();
    } catch (RuntimeException e) {
      responseObserver.onError(AccessErrorMapper.toStatusRuntimeException(e));
    }
  }

  @Override
  public void refresh(
      RefreshRequest request, StreamObserver<AuthenticationResponse> responseObserver) {
    try {
      authorizer.requireConsole();
      var result = authenticationService.refresh(request.getRefreshToken());
      responseObserver.onNext(IdentityMapper.toProto(result));
      responseObserver.onCompleted();
    } catch (RuntimeException e) {
      responseObserver.onError(AccessErrorMapper.toStatusRuntimeException(e));
    }
  }

  @Override
  public void logout(LogoutRequest request, StreamObserver<LogoutResponse> responseObserver) {
    try {
      authorizer.requireConsole();
      authenticationService.logout(
          DecimalId.parse(request.getUserId(), "user_id"),
          DecimalId.parse(request.getSessionFamilyId(), "session_family_id"));
      responseObserver.onNext(LogoutResponse.getDefaultInstance());
      responseObserver.onCompleted();
    } catch (RuntimeException e) {
      responseObserver.onError(AccessErrorMapper.toStatusRuntimeException(e));
    }
  }

  @Override
  public void getJwtVerificationKeys(
      GetJwtVerificationKeysRequest request,
      StreamObserver<JwtVerificationKeySet> responseObserver) {
    try {
      authorizer.requireConsoleOrOpenApi();
      responseObserver.onNext(IdentityMapper.toProto(jwtIssuer.verificationKeys()));
      responseObserver.onCompleted();
    } catch (RuntimeException e) {
      responseObserver.onError(AccessErrorMapper.toStatusRuntimeException(e));
    }
  }
}
