package ai.cerbur.crag.access.grpc.provider;

import ai.cerbur.crag.access.core.apikey.ApiKeyService;
import ai.cerbur.crag.access.grpc.error.AccessErrorMapper;
import ai.cerbur.crag.access.grpc.mapper.ApiKeyMapper;
import ai.cerbur.crag.access.grpc.security.AccessRpcAuthorizer;
import ai.cerbur.crag.contracts.access.v1.ApiKeyScope;
import ai.cerbur.crag.contracts.access.v1.ApiKeyServiceGrpc;
import ai.cerbur.crag.contracts.access.v1.ApiKeyView;
import ai.cerbur.crag.contracts.access.v1.AuthenticateApiKeyRequest;
import ai.cerbur.crag.contracts.access.v1.AuthenticatedApiKey;
import ai.cerbur.crag.contracts.access.v1.BlockScopeRequest;
import ai.cerbur.crag.contracts.access.v1.ChangeApiKeyStateRequest;
import ai.cerbur.crag.contracts.access.v1.CreateApiKeyRequest;
import ai.cerbur.crag.contracts.access.v1.CreatedApiKey;
import ai.cerbur.crag.contracts.access.v1.RegisterScopeRequest;
import ai.cerbur.crag.contracts.access.v1.RotateApiKeyRequest;
import io.grpc.stub.StreamObserver;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** API Key gRPC provider；Console 管理 Scope/Key，Open API 鉴权 Key。 */
@Component
public class ApiKeyGrpcProvider extends ApiKeyServiceGrpc.ApiKeyServiceImplBase {

  @Autowired private ApiKeyService apiKeyService;
  @Autowired private AccessRpcAuthorizer authorizer;

  @Override
  public void registerScope(
      RegisterScopeRequest request, StreamObserver<ApiKeyScope> responseObserver) {
    try {
      authorizer.requireConsole();
      var result =
          apiKeyService.registerScope(
              DecimalId.parse(request.getActorUserId(), "actor_user_id"),
              DecimalId.parse(request.getTenantId(), "tenant_id"),
              DecimalId.parse(request.getKnowledgeBaseId(), "knowledge_base_id"));
      responseObserver.onNext(ApiKeyMapper.toProto(result));
      responseObserver.onCompleted();
    } catch (RuntimeException e) {
      responseObserver.onError(AccessErrorMapper.toStatusRuntimeException(e));
    }
  }

  @Override
  public void blockScope(BlockScopeRequest request, StreamObserver<ApiKeyScope> responseObserver) {
    try {
      authorizer.requireConsole();
      var result =
          apiKeyService.blockScope(
              DecimalId.parse(request.getActorUserId(), "actor_user_id"),
              DecimalId.parse(request.getTenantId(), "tenant_id"),
              DecimalId.parse(request.getKnowledgeBaseId(), "knowledge_base_id"));
      responseObserver.onNext(ApiKeyMapper.toProto(result));
      responseObserver.onCompleted();
    } catch (RuntimeException e) {
      responseObserver.onError(AccessErrorMapper.toStatusRuntimeException(e));
    }
  }

  @Override
  public void createApiKey(
      CreateApiKeyRequest request, StreamObserver<CreatedApiKey> responseObserver) {
    try {
      authorizer.requireConsole();
      var result =
          apiKeyService.create(
              DecimalId.parse(request.getActorUserId(), "actor_user_id"),
              DecimalId.parse(request.getTenantId(), "tenant_id"),
              DecimalId.parse(request.getKnowledgeBaseId(), "knowledge_base_id"),
              request.getName(),
              request.getTtlSeconds() > 0 ? Duration.ofSeconds(request.getTtlSeconds()) : null);
      responseObserver.onNext(ApiKeyMapper.toProto(result));
      responseObserver.onCompleted();
    } catch (RuntimeException e) {
      responseObserver.onError(AccessErrorMapper.toStatusRuntimeException(e));
    }
  }

  @Override
  public void disableApiKey(
      ChangeApiKeyStateRequest request, StreamObserver<ApiKeyView> responseObserver) {
    runStateChange(request, responseObserver, apiKeyService::disable);
  }

  @Override
  public void enableApiKey(
      ChangeApiKeyStateRequest request, StreamObserver<ApiKeyView> responseObserver) {
    runStateChange(request, responseObserver, apiKeyService::enable);
  }

  @Override
  public void revokeApiKey(
      ChangeApiKeyStateRequest request, StreamObserver<ApiKeyView> responseObserver) {
    runStateChange(request, responseObserver, apiKeyService::revoke);
  }

  private void runStateChange(
      ChangeApiKeyStateRequest request,
      StreamObserver<ApiKeyView> responseObserver,
      FunctionTriFunction action) {
    try {
      authorizer.requireConsole();
      var result =
          action.apply(
              DecimalId.parse(request.getActorUserId(), "actor_user_id"),
              DecimalId.parse(request.getTenantId(), "tenant_id"),
              DecimalId.parse(request.getApiKeyId(), "api_key_id"));
      responseObserver.onNext(ApiKeyMapper.toProto(result));
      responseObserver.onCompleted();
    } catch (RuntimeException e) {
      responseObserver.onError(AccessErrorMapper.toStatusRuntimeException(e));
    }
  }

  @Override
  public void rotateApiKey(
      RotateApiKeyRequest request, StreamObserver<CreatedApiKey> responseObserver) {
    try {
      authorizer.requireConsole();
      var result =
          apiKeyService.rotate(
              DecimalId.parse(request.getActorUserId(), "actor_user_id"),
              DecimalId.parse(request.getTenantId(), "tenant_id"),
              DecimalId.parse(request.getApiKeyId(), "api_key_id"),
              request.getTtlSeconds() > 0 ? Duration.ofSeconds(request.getTtlSeconds()) : null);
      responseObserver.onNext(ApiKeyMapper.toProto(result));
      responseObserver.onCompleted();
    } catch (RuntimeException e) {
      responseObserver.onError(AccessErrorMapper.toStatusRuntimeException(e));
    }
  }

  @Override
  public void authenticateApiKey(
      AuthenticateApiKeyRequest request, StreamObserver<AuthenticatedApiKey> responseObserver) {
    try {
      authorizer.requireOpenApi();
      var result = apiKeyService.authenticate(request.getApiKey());
      responseObserver.onNext(ApiKeyMapper.toProto(result));
      responseObserver.onCompleted();
    } catch (RuntimeException e) {
      responseObserver.onError(AccessErrorMapper.toStatusRuntimeException(e));
    }
  }

  /** 三参数函数式接口，便于统一 disable/enable/revoke。 */
  @FunctionalInterface
  interface FunctionTriFunction {
    ai.cerbur.crag.access.core.apikey.ApiKeyResult apply(long actor, long tenant, long apiKeyId);
  }
}
