package ai.cerbur.crag.console.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ai.cerbur.crag.console.knowledge.dto.KnowledgeBaseResponse;
import ai.cerbur.crag.console.knowledge.service.KnowledgeBaseOrchestrator.CreateResult;
import ai.cerbur.crag.console.knowledge.service.KnowledgeBaseOrchestrator.EnsureScopeFailedException;
import ai.cerbur.crag.console.knowledge.service.KnowledgeBaseOrchestrator.ForbiddenException;
import ai.cerbur.crag.console.knowledge.service.KnowledgeBaseOrchestrator.NotFoundException;
import ai.cerbur.crag.contracts.access.v1.ApiKeyScope;
import ai.cerbur.crag.contracts.access.v1.ApiKeyScopeStatus;
import ai.cerbur.crag.contracts.access.v1.ApiKeyServiceGrpc;
import ai.cerbur.crag.contracts.access.v1.AuthorizationDecision;
import ai.cerbur.crag.contracts.access.v1.AuthorizeTenantActionRequest;
import ai.cerbur.crag.contracts.access.v1.EnsureScopeRequest;
import ai.cerbur.crag.contracts.access.v1.MembershipServiceGrpc;
import ai.cerbur.crag.contracts.access.v1.TenantAction;
import ai.cerbur.crag.contracts.knowledge.v1.CreateKnowledgeBaseRequest;
import ai.cerbur.crag.contracts.knowledge.v1.GetKnowledgeBaseRequest;
import ai.cerbur.crag.contracts.knowledge.v1.KnowledgeBase;
import ai.cerbur.crag.contracts.knowledge.v1.KnowledgeBaseServiceGrpc;
import ai.cerbur.crag.contracts.knowledge.v1.ListKnowledgeBasesRequest;
import ai.cerbur.crag.contracts.knowledge.v1.ListKnowledgeBasesResponse;
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
 * KnowledgeBaseOrchestrator 进程内 gRPC 组件测试（plan_21/21.8）。
 *
 * <p>验证 KB 编排：Authorize → Create → EnsureScope；部分成功 201/apiKeyReady=false；不二次 create；list/get 先
 * authorize，跨租户统一 not found。真实跨服务由 21.13 Docker 全链路证明；本测试验证编排决策与 gRPC Status 映射。
 */
@DisplayName("KnowledgeBaseOrchestrator in-process gRPC")
class KnowledgeBaseOrchestratorTest {

  private Server server;
  private ManagedChannel accessChannel;
  private ManagedChannel knowledgeChannel;
  private FakeMembershipService membershipFake;
  private FakeApiKeyService apiKeyFake;
  private FakeKnowledgeBaseService kbFake;
  private KnowledgeBaseOrchestrator orchestrator;

  @BeforeEach
  void setUp() throws IOException {
    membershipFake = new FakeMembershipService();
    apiKeyFake = new FakeApiKeyService();
    kbFake = new FakeKnowledgeBaseService();
    String accessName = InProcessServerBuilder.generateName();
    server =
        InProcessServerBuilder.forName(accessName)
            .directExecutor()
            .addService(membershipFake)
            .addService(apiKeyFake)
            .addService(kbFake)
            .build()
            .start();
    accessChannel = InProcessChannelBuilder.forName(accessName).directExecutor().build();
    knowledgeChannel = accessChannel; // 测试使用同一进程内 channel（Knowledge 与 Access 共享 server）
    orchestrator = new KnowledgeBaseOrchestrator(accessChannel, knowledgeChannel, 5000L);
  }

  @AfterEach
  void tearDown() {
    if (accessChannel != null && !accessChannel.isShutdown()) accessChannel.shutdownNow();
    if (server != null && !server.isShutdown()) server.shutdownNow();
  }

  @Test
  @DisplayName("create 完整成功 → apiKeyReady=true，编排顺序 Authorize→Create→EnsureScope")
  void createFullySucceeds() {
    membershipFake.authorizeAllowed = true;
    kbFake.createdKb = kbProto(1L, 1L, "alice-kb", 100L);
    apiKeyFake.ensureScopeResponse = scopeProto(1L, 1L, 1L);

    CreateResult result = orchestrator.create(123L, 1L, "alice-kb");

    assertThat(result.response().apiKeyReady()).isTrue();
    assertThat(result.response().knowledgeBaseId()).isEqualTo("100");
    // 编排顺序：先 Authorize 再 Create 再 Ensure
    assertThat(membershipFake.authorizeAction).isEqualTo(TenantAction.TENANT_CREATE_KNOWLEDGE_BASE);
    assertThat(kbFake.createCalled).isEqualTo(1);
    assertThat(apiKeyFake.ensureScopeCalled).isEqualTo(1);
    // KB create 用 created_by_user_id = actor
    assertThat(kbFake.createRequest.getCreatedByUserId()).isEqualTo("123");
  }

  @Test
  @DisplayName("create EnsureScope UNAVAILABLE 仍返回 201 apiKeyReady=false，不第二次 create")
  void createPartialSuccessWhenEnsureScopeUnavailable() {
    membershipFake.authorizeAllowed = true;
    kbFake.createdKb = kbProto(1L, 1L, "alice-kb", 100L);
    apiKeyFake.ensureScopeStatus = Status.UNAVAILABLE;

    CreateResult result = orchestrator.create(123L, 1L, "alice-kb");

    // 部分成功：apiKeyReady=false，但资源已创建
    assertThat(result.response().apiKeyReady()).isFalse();
    assertThat(result.response().knowledgeBaseId()).isEqualTo("100");
    // 不第二次 create（避免重复建库）
    assertThat(kbFake.createCalled).isEqualTo(1);
    assertThat(apiKeyFake.ensureScopeCalled).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "create EnsureScope FAILED_PRECONDITION/ALREADY_EXISTS 视为冲突，apiKeyReady=false 但仍 201")
  void createScopeConflictStillReturnsResource() {
    membershipFake.authorizeAllowed = true;
    kbFake.createdKb = kbProto(1L, 1L, "alice-kb", 100L);
    apiKeyFake.ensureScopeStatus = Status.FAILED_PRECONDITION;

    CreateResult result = orchestrator.create(123L, 1L, "alice-kb");

    // 幂等冲突视为部分成功（Access 消费者会补齐 Scope）
    assertThat(result.response().apiKeyReady()).isFalse();
    assertThat(kbFake.createCalled).isEqualTo(1);
  }

  @Test
  @DisplayName("create Authorize 拒绝 → ForbiddenException（不调用 Create/Ensure）")
  void createForbiddenStopsBeforeCreate() {
    membershipFake.authorizeAllowed = false;

    assertThatThrownBy(() -> orchestrator.create(123L, 1L, "alice-kb"))
        .isInstanceOf(ForbiddenException.class);

    assertThat(kbFake.createCalled).isEqualTo(0);
    assertThat(apiKeyFake.ensureScopeCalled).isEqualTo(0);
  }

  @Test
  @DisplayName("create Authorize UNAVAILABLE → DownstreamUnavailableException（不调用 Create）")
  void createAuthorizeUnavailablePropagates() {
    membershipFake.authorizeStatus = Status.UNAVAILABLE;

    assertThatThrownBy(() -> orchestrator.create(123L, 1L, "alice-kb"))
        .isInstanceOf(EnsureScopeFailedException.DownstreamUnavailableException.class);

    assertThat(kbFake.createCalled).isEqualTo(0);
  }

  @Test
  @DisplayName("create KB Create 失败不调用 EnsureScope")
  void createKbFailureSkipsEnsure() {
    membershipFake.authorizeAllowed = true;
    kbFake.createStatus = Status.NOT_FOUND;

    assertThatThrownBy(() -> orchestrator.create(123L, 1L, "alice-kb"))
        .isInstanceOf(NotFoundException.class);

    assertThat(apiKeyFake.ensureScopeCalled).isEqualTo(0);
  }

  @Test
  @DisplayName("list 先 Authorize VIEW 再查 KB；返回分页 + nextPageToken")
  void listAuthorizesThenQueries() {
    membershipFake.authorizeAllowed = true;
    kbFake.listResponse =
        ListKnowledgeBasesResponse.newBuilder()
            .addKnowledgeBases(kbProto(1L, 1L, "kb-1", 100L))
            .addKnowledgeBases(kbProto(1L, 1L, "kb-2", 101L))
            .setNextPageToken("101")
            .build();

    var page = orchestrator.list(123L, 1L, 20, "");

    assertThat(page.items()).hasSize(2);
    assertThat(page.items().get(0).knowledgeBaseId()).isEqualTo("100");
    assertThat(page.nextPageToken()).isEqualTo("101");
    assertThat(membershipFake.authorizeAction).isEqualTo(TenantAction.TENANT_VIEW_KNOWLEDGE_BASE);
  }

  @Test
  @DisplayName("list Authorize 拒绝决策 → ForbiddenException（成员缺权限 → 403）")
  void listForbidden() {
    membershipFake.authorizeAllowed = false;

    assertThatThrownBy(() -> orchestrator.list(123L, 1L, 20, ""))
        .isInstanceOf(ForbiddenException.class);
    assertThat(kbFake.listCalled).isEqualTo(0);
  }

  @Test
  @DisplayName("list KB 跨租户 NOT_FOUND → NotFoundException（不泄漏存在性）")
  void listKbNotFoundMappedToNotFound() {
    membershipFake.authorizeAllowed = true;
    kbFake.listStatus = Status.NOT_FOUND;

    assertThatThrownBy(() -> orchestrator.list(123L, 99L, 20, ""))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  @DisplayName("get 先 Authorize VIEW 再查单 KB；返回详情")
  void getAuthorizesThenQueries() {
    membershipFake.authorizeAllowed = true;
    kbFake.getKb = kbProto(1L, 1L, "kb-1", 100L);

    KnowledgeBaseResponse resp = orchestrator.get(123L, 1L, 100L);

    assertThat(resp.knowledgeBaseId()).isEqualTo("100");
    assertThat(resp.name()).isEqualTo("kb-1");
    assertThat(resp.tenantId()).isEqualTo("1");
    assertThat(membershipFake.authorizeAction).isEqualTo(TenantAction.TENANT_VIEW_KNOWLEDGE_BASE);
  }

  @Test
  @DisplayName("get KB NOT_FOUND → NotFoundException（跨租户/不存在统一不泄漏）")
  void getKbNotFoundReturnsNotFound() {
    membershipFake.authorizeAllowed = true;
    kbFake.getStatus = Status.NOT_FOUND;

    assertThatThrownBy(() -> orchestrator.get(123L, 1L, 999L))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  @DisplayName("跨租户非成员 Authorize NOT_FOUND → NotFoundException（404，不泄漏；21.8 跨租户缺陷回归）")
  void crossTenantNonMemberAuthorizeMapsToNotFound() {
    // 真实 Access 在 21.8 修复后：actor 非该 Tenant 有效成员时 authorizeTenantAction 返回 gRPC NOT_FOUND
    // （MembershipNotFoundException → AccessErrorMapper），而非 deny 决策。Console 须统一映射为 404，
    // 不泄漏租户/资源存在性。list/get/upload（经 kbOrchestrator.get）共用此 authorize 路径。
    membershipFake.authorizeStatus = Status.NOT_FOUND;

    assertThatThrownBy(() -> orchestrator.list(123L, 99L, 20, ""))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> orchestrator.get(123L, 99L, 100L))
        .isInstanceOf(NotFoundException.class);
    assertThat(kbFake.listCalled).isEqualTo(0);
  }

  // ---- helpers / fakes ----

  private static KnowledgeBase kbProto(
      long tenantId, long createdBy, String name, long knowledgeBaseId) {
    long now = Instant.parse("2026-06-29T00:00:00Z").toEpochMilli();
    return KnowledgeBase.newBuilder()
        .setKnowledgeBaseId(Long.toString(knowledgeBaseId))
        .setTenantId(Long.toString(tenantId))
        .setName(name)
        .setCreatedByUserId(Long.toString(createdBy))
        .setStatus("ACTIVE")
        .setCreatedAtEpochMillis(now)
        .setUpdatedAtEpochMillis(now)
        .setVersion(1L)
        .build();
  }

  private static ApiKeyScope scopeProto(long tenantId, long knowledgeBaseId, long version) {
    return ApiKeyScope.newBuilder()
        .setKnowledgeBaseId(Long.toString(knowledgeBaseId))
        .setTenantId(Long.toString(tenantId))
        .setStatus(ApiKeyScopeStatus.SCOPE_STATUS_ACTIVE)
        .setVersion(version)
        .setKeyVersion(1L)
        .setScopeVersion(1L)
        .build();
  }

  static class FakeMembershipService extends MembershipServiceGrpc.MembershipServiceImplBase {
    boolean authorizeAllowed = true;
    Status authorizeStatus = Status.OK;
    TenantAction authorizeAction;

    @Override
    public void authorizeTenantAction(
        AuthorizeTenantActionRequest request, StreamObserver<AuthorizationDecision> resp) {
      authorizeAction = request.getAction();
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
    ApiKeyScope ensureScopeResponse = ApiKeyScope.getDefaultInstance();
    int ensureScopeCalled = 0;

    @Override
    public void ensureScope(EnsureScopeRequest request, StreamObserver<ApiKeyScope> resp) {
      ensureScopeCalled++;
      if (ensureScopeStatus != Status.OK) {
        resp.onError(ensureScopeStatus.asRuntimeException());
        return;
      }
      resp.onNext(ensureScopeResponse);
      resp.onCompleted();
    }
  }

  static class FakeKnowledgeBaseService
      extends KnowledgeBaseServiceGrpc.KnowledgeBaseServiceImplBase {
    KnowledgeBase createdKb = KnowledgeBase.getDefaultInstance();
    Status createStatus = Status.OK;
    int createCalled = 0;
    CreateKnowledgeBaseRequest createRequest;

    Status listStatus = Status.OK;
    ListKnowledgeBasesResponse listResponse = ListKnowledgeBasesResponse.getDefaultInstance();
    int listCalled = 0;

    KnowledgeBase getKb = KnowledgeBase.getDefaultInstance();
    Status getStatus = Status.OK;

    @Override
    public void createKnowledgeBase(
        CreateKnowledgeBaseRequest request, StreamObserver<KnowledgeBase> resp) {
      createCalled++;
      createRequest = request;
      if (createStatus != Status.OK) {
        resp.onError(createStatus.asRuntimeException());
        return;
      }
      resp.onNext(createdKb);
      resp.onCompleted();
    }

    @Override
    public void listKnowledgeBases(
        ListKnowledgeBasesRequest request, StreamObserver<ListKnowledgeBasesResponse> resp) {
      listCalled++;
      if (listStatus != Status.OK) {
        resp.onError(listStatus.asRuntimeException());
        return;
      }
      resp.onNext(listResponse);
      resp.onCompleted();
    }

    @Override
    public void getKnowledgeBase(
        GetKnowledgeBaseRequest request, StreamObserver<KnowledgeBase> resp) {
      if (getStatus != Status.OK) {
        resp.onError(getStatus.asRuntimeException());
        return;
      }
      resp.onNext(getKb);
      resp.onCompleted();
    }
  }
}
