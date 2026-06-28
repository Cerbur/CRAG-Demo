package ai.cerbur.crag.console.apikey.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.cerbur.crag.console.apikey.dto.ApiKeyListResponse;
import ai.cerbur.crag.console.apikey.dto.ApiKeyResponse;
import ai.cerbur.crag.console.apikey.dto.CreatedApiKeyResponse;
import ai.cerbur.crag.console.apikey.service.ApiKeyOrchestrator.ConflictException;
import ai.cerbur.crag.console.apikey.service.ApiKeyOrchestrator.DownstreamUnavailableException;
import ai.cerbur.crag.console.apikey.service.ApiKeyOrchestrator.ForbiddenException;
import ai.cerbur.crag.console.apikey.service.ApiKeyOrchestrator.NotFoundException;
import ai.cerbur.crag.contracts.access.v1.ApiKeyScope;
import ai.cerbur.crag.contracts.access.v1.ApiKeyScopeStatus;
import ai.cerbur.crag.contracts.access.v1.ApiKeyServiceGrpc;
import ai.cerbur.crag.contracts.access.v1.ApiKeyStatus;
import ai.cerbur.crag.contracts.access.v1.ApiKeyView;
import ai.cerbur.crag.contracts.access.v1.AuthorizationDecision;
import ai.cerbur.crag.contracts.access.v1.AuthorizeTenantActionRequest;
import ai.cerbur.crag.contracts.access.v1.ChangeApiKeyStateRequest;
import ai.cerbur.crag.contracts.access.v1.CreateApiKeyRequest;
import ai.cerbur.crag.contracts.access.v1.CreatedApiKey;
import ai.cerbur.crag.contracts.access.v1.EnsureScopeRequest;
import ai.cerbur.crag.contracts.access.v1.GetApiKeyRequest;
import ai.cerbur.crag.contracts.access.v1.ListApiKeysRequest;
import ai.cerbur.crag.contracts.access.v1.ListApiKeysResponse;
import ai.cerbur.crag.contracts.access.v1.MembershipServiceGrpc;
import ai.cerbur.crag.contracts.access.v1.RotateApiKeyRequest;
import ai.cerbur.crag.contracts.access.v1.TenantAction;
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
 * ApiKeyOrchestrator 进程内 gRPC 组件测试（plan_21/21.9）。
 *
 * <p>验证七个 operation（list/get/create/disable/enable/rotate/revoke）的编排：先 KB 归属 Authorize
 * (MANAGE_API_KEY) → EnsureScope 兜底 → Access Key gRPC 调用，actor 只来自参数（不读 body），跨租户/不存在 统一
 * NOT_FOUND，状态冲突 FAILED_PRECONDITION → ConflictException（409）。真实跨服务由 21.13 Docker 全链路证明。
 */
@DisplayName("ApiKeyOrchestrator in-process gRPC")
class ApiKeyOrchestratorTest {

  private Server server;
  private ManagedChannel channel;
  private FakeMembershipService membershipFake;
  private FakeApiKeyService apiKeyFake;
  private ApiKeyOrchestrator orchestrator;

  @BeforeEach
  void setUp() throws IOException {
    membershipFake = new FakeMembershipService();
    apiKeyFake = new FakeApiKeyService();
    String name = InProcessServerBuilder.generateName();
    server =
        InProcessServerBuilder.forName(name)
            .directExecutor()
            .addService(membershipFake)
            .addService(apiKeyFake)
            .build()
            .start();
    channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    orchestrator = new ApiKeyOrchestrator(channel, 5000L);
  }

  @AfterEach
  void tearDown() {
    if (channel != null && !channel.isShutdown()) channel.shutdownNow();
    if (server != null && !server.isShutdown()) server.shutdownNow();
  }

  // ---- list ----

  @Test
  @DisplayName("list 先 Authorize MANAGE_API_KEY → EnsureScope → 返回分页 + 前缀投影（不含完整 Key）")
  void listAuthorizesThenQueries() {
    membershipFake.authorizeAllowed = true;
    apiKeyFake.ensureScopeOk = true;
    apiKeyFake.listResponse =
        ListApiKeysResponse.newBuilder()
            .addApiKeys(viewProto(200L, 1L, 100L, "prod-key", ApiKeyStatus.KEY_ACTIVE, "crag_abc"))
            .addApiKeys(viewProto(201L, 1L, 100L, "staging", ApiKeyStatus.KEY_DISABLED, "crag_def"))
            .setNextPageToken("201")
            .build();

    ApiKeyListResponse page = orchestrator.list(123L, 1L, 100L, 20, "");

    assertThat(page.items()).hasSize(2);
    ApiKeyResponse first = page.items().get(0);
    assertThat(first.apiKeyId()).isEqualTo("200");
    assertThat(first.knowledgeBaseId()).isEqualTo("100");
    assertThat(first.name()).isEqualTo("prod-key");
    assertThat(first.status()).isEqualTo("ACTIVE");
    // 列表只暴露前缀，不含完整 Key
    assertThat(first.keyPrefix()).isEqualTo("crag_abc");
    assertThat(page.nextPageToken()).isEqualTo("201");
    // actor 与 KB 都来自参数
    assertThat(membershipFake.lastAction).isEqualTo(TenantAction.TENANT_MANAGE_API_KEY);
    assertThat(apiKeyFake.lastListTenantId).isEqualTo("1");
    assertThat(apiKeyFake.lastListKnowledgeBaseId).isEqualTo("100");
    // EnsureScope 兜底执行一次
    assertThat(apiKeyFake.ensureScopeCalls).isEqualTo(1);
  }

  @Test
  @DisplayName("list MEMBER 越权 → ForbiddenException（403），不调 List/Ensure")
  void listMemberForbidden() {
    membershipFake.authorizeAllowed = false;

    assertThatThrownBy(() -> orchestrator.list(123L, 1L, 100L, 20, ""))
        .isInstanceOf(ForbiddenException.class);
    assertThat(apiKeyFake.listCalls).isEqualTo(0);
    assertThat(apiKeyFake.ensureScopeCalls).isEqualTo(0);
  }

  @Test
  @DisplayName("list Authorize NOT_FOUND → NotFoundException（跨租户不泄漏）")
  void listAuthorizeNotFound() {
    membershipFake.authorizeStatus = Status.NOT_FOUND;

    assertThatThrownBy(() -> orchestrator.list(123L, 99L, 100L, 20, ""))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  @DisplayName("list EnsureScope 失败仍降级返回列表（不阻塞）")
  void listEnsureScopeFailureDegrades() {
    membershipFake.authorizeAllowed = true;
    apiKeyFake.ensureScopeStatus = Status.UNAVAILABLE;
    apiKeyFake.listResponse =
        ListApiKeysResponse.newBuilder()
            .addApiKeys(viewProto(200L, 1L, 100L, "k", ApiKeyStatus.KEY_ACTIVE, "crag_x"))
            .build();

    ApiKeyListResponse page = orchestrator.list(123L, 1L, 100L, 20, "");
    // EnsureScope 降级，列表仍返回
    assertThat(page.items()).hasSize(1);
  }

  @Test
  @DisplayName("list Access NOT_FOUND → NotFoundException（跨 KB 不泄漏）")
  void listAccessNotFound() {
    membershipFake.authorizeAllowed = true;
    apiKeyFake.ensureScopeOk = true;
    apiKeyFake.listStatus = Status.NOT_FOUND;

    assertThatThrownBy(() -> orchestrator.list(123L, 1L, 999L, 20, ""))
        .isInstanceOf(NotFoundException.class);
  }

  // ---- get ----

  @Test
  @DisplayName("get 先 Authorize → EnsureScope → 返回单条前缀投影")
  void getAuthorizesThenQueries() {
    membershipFake.authorizeAllowed = true;
    apiKeyFake.ensureScopeOk = true;
    apiKeyFake.getResponse =
        viewProto(200L, 1L, 100L, "prod-key", ApiKeyStatus.KEY_ACTIVE, "crag_abc");

    ApiKeyResponse resp = orchestrator.get(123L, 1L, 100L, 200L);
    assertThat(resp.apiKeyId()).isEqualTo("200");
    assertThat(resp.status()).isEqualTo("ACTIVE");
    assertThat(apiKeyFake.lastGetApiKeyId).isEqualTo("200");
  }

  @Test
  @DisplayName("get Access NOT_FOUND → NotFoundException（跨 KB 不泄漏）")
  void getNotFound() {
    membershipFake.authorizeAllowed = true;
    apiKeyFake.ensureScopeOk = true;
    apiKeyFake.getStatus = Status.NOT_FOUND;

    assertThatThrownBy(() -> orchestrator.get(123L, 1L, 100L, 200L))
        .isInstanceOf(NotFoundException.class);
  }

  // ---- create ----

  @Test
  @DisplayName("create 返回 CreatedApiKeyResponse（含一次性 completeKey）")
  void createReturnsOneTimeSecret() {
    membershipFake.authorizeAllowed = true;
    apiKeyFake.ensureScopeOk = true;
    long expires = Instant.parse("2026-09-29T00:00:00Z").toEpochMilli();
    apiKeyFake.createdResponse =
        CreatedApiKey.newBuilder()
            .setApiKeyId("200")
            .setTenantId("1")
            .setKnowledgeBaseId("100")
            .setName("prod-key")
            .setCompleteKey("crag_abc_secretvalue")
            .setExpiresAtEpochMillis(Long.toString(expires))
            .build();

    CreatedApiKeyResponse resp = orchestrator.create(123L, 1L, 100L, "prod-key", 0L);
    assertThat(resp.apiKeyId()).isEqualTo("200");
    assertThat(resp.knowledgeBaseId()).isEqualTo("100");
    assertThat(resp.name()).isEqualTo("prod-key");
    // 一次性完整 Key 出现在 Created DTO
    assertThat(resp.completeKey()).isEqualTo("crag_abc_secretvalue");
    assertThat(apiKeyFake.lastCreateName).isEqualTo("prod-key");
    assertThat(apiKeyFake.lastCreateKnowledgeBaseId).isEqualTo("100");
  }

  @Test
  @DisplayName("create MEMBER 越权 → ForbiddenException（403），不调 Access Key")
  void createMemberForbidden() {
    membershipFake.authorizeAllowed = false;

    assertThatThrownBy(() -> orchestrator.create(123L, 1L, 100L, "k", 0L))
        .isInstanceOf(ForbiddenException.class);
    assertThat(apiKeyFake.createCalls).isEqualTo(0);
  }

  @Test
  @DisplayName("create Access INVALID_ARGUMENT → IllegalArgumentException")
  void createInvalidArgument() {
    membershipFake.authorizeAllowed = true;
    apiKeyFake.ensureScopeOk = true;
    apiKeyFake.createStatus = Status.INVALID_ARGUMENT;

    assertThatThrownBy(() -> orchestrator.create(123L, 1L, 100L, "k", 0L))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ---- disable/enable/rotate/revoke 状态冲突 ----

  @Test
  @DisplayName("disable 返回 DISABLED 投影")
  void disableReturnsDisabled() {
    membershipFake.authorizeAllowed = true;
    apiKeyFake.ensureScopeOk = true;
    apiKeyFake.stateChangeResponse =
        viewProto(200L, 1L, 100L, "k", ApiKeyStatus.KEY_DISABLED, "crag_abc");

    ApiKeyResponse resp = orchestrator.disable(123L, 1L, 100L, 200L);
    assertThat(resp.status()).isEqualTo("DISABLED");
  }

  @Test
  @DisplayName("disable 已 DISABLED → FAILED_PRECONDITION → ConflictException（409）")
  void disableAlreadyDisabledConflict() {
    membershipFake.authorizeAllowed = true;
    apiKeyFake.ensureScopeOk = true;
    apiKeyFake.stateChangeStatus = Status.FAILED_PRECONDITION;

    assertThatThrownBy(() -> orchestrator.disable(123L, 1L, 100L, 200L))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  @DisplayName("enable 返回 ACTIVE 投影")
  void enableReturnsActive() {
    membershipFake.authorizeAllowed = true;
    apiKeyFake.ensureScopeOk = true;
    apiKeyFake.stateChangeResponse =
        viewProto(200L, 1L, 100L, "k", ApiKeyStatus.KEY_ACTIVE, "crag_abc");

    ApiKeyResponse resp = orchestrator.enable(123L, 1L, 100L, 200L);
    assertThat(resp.status()).isEqualTo("ACTIVE");
  }

  @Test
  @DisplayName("revoke 返回 REVOKED 投影")
  void revokeReturnsRevoked() {
    membershipFake.authorizeAllowed = true;
    apiKeyFake.ensureScopeOk = true;
    apiKeyFake.stateChangeResponse =
        viewProto(200L, 1L, 100L, "k", ApiKeyStatus.KEY_REVOKED, "crag_abc");

    ApiKeyResponse resp = orchestrator.revoke(123L, 1L, 100L, 200L);
    assertThat(resp.status()).isEqualTo("REVOKED");
  }

  @Test
  @DisplayName("revoke 已 REVOKED → FAILED_PRECONDITION → ConflictException（409）")
  void revokeAlreadyRevokedConflict() {
    membershipFake.authorizeAllowed = true;
    apiKeyFake.ensureScopeOk = true;
    apiKeyFake.stateChangeStatus = Status.FAILED_PRECONDITION;

    assertThatThrownBy(() -> orchestrator.revoke(123L, 1L, 100L, 200L))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  @DisplayName("rotate 返回新 CreatedApiKeyResponse（一次性新秘密）")
  void rotateReturnsNewSecret() {
    membershipFake.authorizeAllowed = true;
    apiKeyFake.ensureScopeOk = true;
    long expires = Instant.parse("2026-09-29T00:00:00Z").toEpochMilli();
    apiKeyFake.rotateResponse =
        CreatedApiKey.newBuilder()
            .setApiKeyId("201")
            .setTenantId("1")
            .setKnowledgeBaseId("100")
            .setName("prod-key")
            .setCompleteKey("crag_xyz_newsecret")
            .setExpiresAtEpochMillis(Long.toString(expires))
            .build();

    CreatedApiKeyResponse resp = orchestrator.rotate(123L, 1L, 100L, 200L, 0L);
    assertThat(resp.apiKeyId()).isEqualTo("201");
    assertThat(resp.completeKey()).isEqualTo("crag_xyz_newsecret");
    assertThat(apiKeyFake.lastRotateApiKeyId).isEqualTo("200");
  }

  @Test
  @DisplayName("rotate 已 DISABLED/REVOKED → FAILED_PRECONDITION → ConflictException（409）")
  void rotateInvalidStateConflict() {
    membershipFake.authorizeAllowed = true;
    apiKeyFake.ensureScopeOk = true;
    apiKeyFake.rotateStatus = Status.FAILED_PRECONDITION;

    assertThatThrownBy(() -> orchestrator.rotate(123L, 1L, 100L, 200L, 0L))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  @DisplayName("Access PERMISSION_DENIED（MEMBER 无权）→ ForbiddenException")
  void accessPermissionDeniedMapsForbidden() {
    membershipFake.authorizeAllowed = true;
    apiKeyFake.ensureScopeOk = true;
    apiKeyFake.stateChangeStatus = Status.PERMISSION_DENIED;

    assertThatThrownBy(() -> orchestrator.disable(123L, 1L, 100L, 200L))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  @DisplayName("Access UNAVAILABLE → DownstreamUnavailableException（503）")
  void accessUnavailableMapsDownstream() {
    membershipFake.authorizeAllowed = true;
    apiKeyFake.ensureScopeOk = true;
    apiKeyFake.stateChangeStatus = Status.UNAVAILABLE;

    assertThatThrownBy(() -> orchestrator.disable(123L, 1L, 100L, 200L))
        .isInstanceOf(DownstreamUnavailableException.class);
  }

  // ---- helpers / fakes ----

  private static ApiKeyView viewProto(
      long apiKeyId,
      long tenantId,
      long knowledgeBaseId,
      String name,
      ApiKeyStatus status,
      String prefix) {
    long now = Instant.parse("2026-06-29T00:00:00Z").toEpochMilli();
    long expires = Instant.parse("2026-09-29T00:00:00Z").toEpochMilli();
    return ApiKeyView.newBuilder()
        .setApiKeyId(Long.toString(apiKeyId))
        .setTenantId(Long.toString(tenantId))
        .setKnowledgeBaseId(Long.toString(knowledgeBaseId))
        .setName(name)
        .setStatus(status)
        .setKeyPrefix(prefix)
        .setCreatedAtEpochMillis(Long.toString(now))
        .setExpiresAtEpochMillis(Long.toString(expires))
        .setVersion(1L)
        .build();
  }

  static class FakeMembershipService extends MembershipServiceGrpc.MembershipServiceImplBase {
    boolean authorizeAllowed = true;
    Status authorizeStatus = Status.OK;
    TenantAction lastAction;

    @Override
    public void authorizeTenantAction(
        AuthorizeTenantActionRequest request, StreamObserver<AuthorizationDecision> resp) {
      lastAction = request.getAction();
      if (authorizeStatus != Status.OK) {
        resp.onError(authorizeStatus.asRuntimeException());
        return;
      }
      resp.onNext(
          AuthorizationDecision.newBuilder()
              .setAllowed(authorizeAllowed)
              .setAction(request.getAction())
              .build());
      resp.onCompleted();
    }
  }

  static class FakeApiKeyService extends ApiKeyServiceGrpc.ApiKeyServiceImplBase {
    Status ensureScopeStatus = Status.OK;
    boolean ensureScopeOk = false;
    int ensureScopeCalls = 0;

    Status listStatus = Status.OK;
    ListApiKeysResponse listResponse = ListApiKeysResponse.getDefaultInstance();
    int listCalls = 0;
    String lastListTenantId;
    String lastListKnowledgeBaseId;

    ApiKeyView getResponse = ApiKeyView.getDefaultInstance();
    Status getStatus = Status.OK;
    String lastGetApiKeyId;

    CreatedApiKey createdResponse = CreatedApiKey.getDefaultInstance();
    Status createStatus = Status.OK;
    int createCalls = 0;
    String lastCreateName;
    String lastCreateKnowledgeBaseId;

    ApiKeyView stateChangeResponse = ApiKeyView.getDefaultInstance();
    Status stateChangeStatus = Status.OK;

    CreatedApiKey rotateResponse = CreatedApiKey.getDefaultInstance();
    Status rotateStatus = Status.OK;
    String lastRotateApiKeyId;

    @Override
    public void ensureScope(EnsureScopeRequest request, StreamObserver<ApiKeyScope> resp) {
      ensureScopeCalls++;
      if (ensureScopeStatus != Status.OK) {
        resp.onError(ensureScopeStatus.asRuntimeException());
        return;
      }
      resp.onNext(
          ApiKeyScope.newBuilder()
              .setKnowledgeBaseId(request.getKnowledgeBaseId())
              .setTenantId(request.getTenantId())
              .setStatus(
                  ensureScopeOk
                      ? ApiKeyScopeStatus.SCOPE_STATUS_ACTIVE
                      : ApiKeyScopeStatus.SCOPE_STATUS_BLOCKED)
              .setVersion(1L)
              .setKeyVersion(1L)
              .setScopeVersion(1L)
              .build());
      resp.onCompleted();
    }

    @Override
    public void listApiKeys(ListApiKeysRequest request, StreamObserver<ListApiKeysResponse> resp) {
      listCalls++;
      lastListTenantId = request.getTenantId();
      lastListKnowledgeBaseId = request.getKnowledgeBaseId();
      if (listStatus != Status.OK) {
        resp.onError(listStatus.asRuntimeException());
        return;
      }
      resp.onNext(listResponse);
      resp.onCompleted();
    }

    @Override
    public void getApiKey(GetApiKeyRequest request, StreamObserver<ApiKeyView> resp) {
      lastGetApiKeyId = request.getApiKeyId();
      if (getStatus != Status.OK) {
        resp.onError(getStatus.asRuntimeException());
        return;
      }
      resp.onNext(getResponse);
      resp.onCompleted();
    }

    @Override
    public void createApiKey(CreateApiKeyRequest request, StreamObserver<CreatedApiKey> resp) {
      createCalls++;
      lastCreateName = request.getName();
      lastCreateKnowledgeBaseId = request.getKnowledgeBaseId();
      if (createStatus != Status.OK) {
        resp.onError(createStatus.asRuntimeException());
        return;
      }
      resp.onNext(createdResponse);
      resp.onCompleted();
    }

    @Override
    public void disableApiKey(ChangeApiKeyStateRequest request, StreamObserver<ApiKeyView> resp) {
      respondStateChange(resp);
    }

    @Override
    public void enableApiKey(ChangeApiKeyStateRequest request, StreamObserver<ApiKeyView> resp) {
      respondStateChange(resp);
    }

    @Override
    public void revokeApiKey(ChangeApiKeyStateRequest request, StreamObserver<ApiKeyView> resp) {
      respondStateChange(resp);
    }

    @Override
    public void rotateApiKey(RotateApiKeyRequest request, StreamObserver<CreatedApiKey> resp) {
      lastRotateApiKeyId = request.getApiKeyId();
      if (rotateStatus != Status.OK) {
        resp.onError(rotateStatus.asRuntimeException());
        return;
      }
      resp.onNext(rotateResponse);
      resp.onCompleted();
    }

    private void respondStateChange(StreamObserver<ApiKeyView> resp) {
      if (stateChangeStatus != Status.OK) {
        resp.onError(stateChangeStatus.asRuntimeException());
        return;
      }
      resp.onNext(stateChangeResponse);
      resp.onCompleted();
    }
  }
}
