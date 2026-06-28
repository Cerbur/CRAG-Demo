package ai.cerbur.crag.console.knowledge.service;

import ai.cerbur.crag.console.knowledge.dto.KnowledgeBaseListResponse;
import ai.cerbur.crag.console.knowledge.dto.KnowledgeBaseResponse;
import ai.cerbur.crag.contracts.access.v1.ApiKeyScope;
import ai.cerbur.crag.contracts.access.v1.ApiKeyServiceGrpc;
import ai.cerbur.crag.contracts.access.v1.AuthorizationDecision;
import ai.cerbur.crag.contracts.access.v1.AuthorizeTenantActionRequest;
import ai.cerbur.crag.contracts.access.v1.EnsureScopeRequest;
import ai.cerbur.crag.contracts.access.v1.GetScopeRequest;
import ai.cerbur.crag.contracts.access.v1.MembershipServiceGrpc;
import ai.cerbur.crag.contracts.access.v1.TenantAction;
import ai.cerbur.crag.contracts.knowledge.v1.CreateKnowledgeBaseRequest;
import ai.cerbur.crag.contracts.knowledge.v1.GetKnowledgeBaseRequest;
import ai.cerbur.crag.contracts.knowledge.v1.KnowledgeBase;
import ai.cerbur.crag.contracts.knowledge.v1.KnowledgeBaseServiceGrpc;
import ai.cerbur.crag.contracts.knowledge.v1.ListKnowledgeBasesRequest;
import ai.cerbur.crag.contracts.knowledge.v1.ListKnowledgeBasesResponse;
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
 * Console KnowledgeBase 编排适配器（plan_21/21.8）。
 *
 * <p>建库编排：Authorize(TENANT_CREATE_KNOWLEDGE_BASE) → KnowledgeBase Create → Access EnsureScope。Scope
 * 暂时 失败时仍返回已创建资源（HTTP 201 + {@code apiKeyReady=false}），不二次 create，不假装跨服务回滚。Access 消费者会 通过 {@code
 * KNOWLEDGE_BASE_CREATED} 事件补齐 Scope。
 *
 * <p>list/get 先 Authorize(TENANT_VIEW_KNOWLEDGE_BASE) 再查 KB；跨租户/不存在统一 NOT_FOUND，不泄漏存在性。列表项的 {@code
 * apiKeyReady} 由批量 GetScope 补齐；查询失败时降级为 {@code false}，不阻断列表返回。
 *
 * <p>actor userId 只来自 ConsolePrincipal；非幂等 Create 不自动重试。所有 ID 在 gRPC 中使用十进制字符串。
 */
@Component
public class KnowledgeBaseOrchestrator {

  private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseOrchestrator.class);

  private final MembershipServiceGrpc.MembershipServiceBlockingStub membershipStub;
  private final ApiKeyServiceGrpc.ApiKeyServiceBlockingStub apiKeyStub;
  private final KnowledgeBaseServiceGrpc.KnowledgeBaseServiceBlockingStub kbStub;
  private final long deadlineMillis;

  @Autowired
  public KnowledgeBaseOrchestrator(
      @Qualifier("consoleAccessChannel") ManagedChannel accessChannel,
      @Qualifier("consoleKnowledgeChannel") ManagedChannel knowledgeChannel,
      @Value("${crag.grpc.client.max-deadline-millis:10000}") long deadlineMillis) {
    this.membershipStub = MembershipServiceGrpc.newBlockingStub(accessChannel);
    this.apiKeyStub = ApiKeyServiceGrpc.newBlockingStub(accessChannel);
    this.kbStub = KnowledgeBaseServiceGrpc.newBlockingStub(knowledgeChannel);
    this.deadlineMillis = deadlineMillis;
  }

  /** 建库编排结果：可序列化的 KnowledgeBaseResponse + apiKeyReady 已写入 response。 */
  public record CreateResult(KnowledgeBaseResponse response) {}

  /**
   * 建库：Authorize → Create → EnsureScope。
   *
   * <p>Scope 暂时失败（UNAVAILABLE）或幂等冲突（FAILED_PRECONDITION/ALREADY_EXISTS）视为部分成功，返回 {@code
   * apiKeyReady=false} 但仍 201。其他 EnsureScope 错误（INVALID_ARGUMENT/NOT_FOUND/PERMISSION_DENIED）按设计不属于
   * 部分成功语义，交由异常映射；本方法在 Scope 阶段捕获的瞬时/幂等错误统一降级为 {@code apiKeyReady=false}。
   */
  public CreateResult create(long actorUserId, long tenantId, String name) {
    authorize(actorUserId, tenantId, TenantAction.TENANT_CREATE_KNOWLEDGE_BASE);
    KnowledgeBase kb = createKnowledgeBase(actorUserId, tenantId, name);
    boolean apiKeyReady =
        ensureScopeQuiet(actorUserId, tenantId, parseLong(kb.getKnowledgeBaseId()));
    return new CreateResult(toResponse(kb, apiKeyReady));
  }

  /** 列表：Authorize VIEW → KB list → 批量补齐 apiKeyReady。 */
  public KnowledgeBaseListResponse list(
      long actorUserId, long tenantId, int pageSize, String pageToken) {
    authorize(actorUserId, tenantId, TenantAction.TENANT_VIEW_KNOWLEDGE_BASE);
    try {
      ListKnowledgeBasesResponse resp =
          kbStubWithDeadline()
              .listKnowledgeBases(
                  ListKnowledgeBasesRequest.newBuilder()
                      .setTenantId(Long.toString(tenantId))
                      .setPageSize(pageSize)
                      .setPageToken(pageToken == null ? "" : pageToken)
                      .build());
      List<KnowledgeBaseResponse> items = new ArrayList<>();
      for (KnowledgeBase kb : resp.getKnowledgeBasesList()) {
        boolean ready =
            checkScopeReadyQuiet(actorUserId, tenantId, parseLong(kb.getKnowledgeBaseId()));
        items.add(toResponse(kb, ready));
      }
      String next = resp.getNextPageToken();
      return new KnowledgeBaseListResponse(items, next == null || next.isEmpty() ? null : next);
    } catch (StatusRuntimeException e) {
      throw mapKnowledgeBase(e);
    }
  }

  /** 详情：Authorize VIEW → KB get。 */
  public KnowledgeBaseResponse get(long actorUserId, long tenantId, long knowledgeBaseId) {
    authorize(actorUserId, tenantId, TenantAction.TENANT_VIEW_KNOWLEDGE_BASE);
    try {
      KnowledgeBase kb =
          kbStubWithDeadline()
              .getKnowledgeBase(
                  GetKnowledgeBaseRequest.newBuilder()
                      .setTenantId(Long.toString(tenantId))
                      .setKnowledgeBaseId(Long.toString(knowledgeBaseId))
                      .build());
      boolean ready = checkScopeReadyQuiet(actorUserId, tenantId, knowledgeBaseId);
      return toResponse(kb, ready);
    } catch (StatusRuntimeException e) {
      throw mapKnowledgeBase(e);
    }
  }

  // ---- helpers ----

  private void authorize(long actorUserId, long tenantId, TenantAction action) {
    try {
      AuthorizationDecision decision =
          membershipStubWithDeadline()
              .authorizeTenantAction(
                  AuthorizeTenantActionRequest.newBuilder()
                      .setActorUserId(Long.toString(actorUserId))
                      .setTenantId(Long.toString(tenantId))
                      .setAction(action)
                      .build());
      if (!decision.getAllowed()) {
        throw new ForbiddenException();
      }
    } catch (StatusRuntimeException e) {
      throw mapAuthorize(e);
    }
  }

  private KnowledgeBase createKnowledgeBase(long actorUserId, long tenantId, String name) {
    try {
      return kbStubWithDeadline()
          .createKnowledgeBase(
              CreateKnowledgeBaseRequest.newBuilder()
                  .setTenantId(Long.toString(tenantId))
                  .setCreatedByUserId(Long.toString(actorUserId))
                  .setName(name)
                  .build());
    } catch (StatusRuntimeException e) {
      throw mapKnowledgeBase(e);
    }
  }

  /**
   * EnsureScope 但允许部分成功：UNAVAILABLE/DEADLINE_EXCEEDED/ABORTED/FAILED_PRECONDITION/ALREADY_EXISTS
   * 降级为 apiKeyReady=false（不抛异常）；其余错误按异常映射。返回 true 表示 Scope ACTIVE 就绪。
   */
  private boolean ensureScopeQuiet(long actorUserId, long tenantId, long knowledgeBaseId) {
    try {
      ApiKeyScope scope =
          apiKeyStubWithDeadline()
              .ensureScope(
                  EnsureScopeRequest.newBuilder()
                      .setActorUserId(Long.toString(actorUserId))
                      .setTenantId(Long.toString(tenantId))
                      .setKnowledgeBaseId(Long.toString(knowledgeBaseId))
                      .build());
      return scope.getStatus().getNumber()
          == ai.cerbur.crag.contracts.access.v1.ApiKeyScopeStatus.SCOPE_STATUS_ACTIVE.getNumber();
    } catch (StatusRuntimeException e) {
      Status.Code code = e.getStatus().getCode();
      if (code == Status.Code.UNAVAILABLE
          || code == Status.Code.DEADLINE_EXCEEDED
          || code == Status.Code.ABORTED
          || code == Status.Code.FAILED_PRECONDITION
          || code == Status.Code.ALREADY_EXISTS) {
        // 部分成功：Scope 暂时未就绪，资源已创建；Access 消费者将补齐
        log.warn(
            "EnsureScope 部分失败降级 apiKeyReady=false — kb={} code={} desc={}",
            knowledgeBaseId,
            code,
            e.getStatus().getDescription());
        return false;
      }
      // 其余错误（INVALID_ARGUMENT/NOT_FOUND/PERMISSION_DENIED/INTERNAL/UNKNOWN）按异常映射，不假装成功
      throw mapScope(e);
    }
  }

  /** GetScope 查询 Scope 就绪状态；查询失败降级为 false，不阻断列表/详情。 */
  private boolean checkScopeReadyQuiet(long actorUserId, long tenantId, long knowledgeBaseId) {
    try {
      ApiKeyScope scope =
          apiKeyStubWithDeadline()
              .getScope(
                  GetScopeRequest.newBuilder()
                      .setActorUserId(Long.toString(actorUserId))
                      .setTenantId(Long.toString(tenantId))
                      .setKnowledgeBaseId(Long.toString(knowledgeBaseId))
                      .build());
      return scope.getStatus().getNumber()
          == ai.cerbur.crag.contracts.access.v1.ApiKeyScopeStatus.SCOPE_STATUS_ACTIVE.getNumber();
    } catch (StatusRuntimeException e) {
      log.debug(
          "GetScope 降级 apiKeyReady=false — kb={} code={}",
          knowledgeBaseId,
          e.getStatus().getCode());
      return false;
    }
  }

  private MembershipServiceGrpc.MembershipServiceBlockingStub membershipStubWithDeadline() {
    return withDeadline(membershipStub);
  }

  private ApiKeyServiceGrpc.ApiKeyServiceBlockingStub apiKeyStubWithDeadline() {
    return withDeadline(apiKeyStub);
  }

  private KnowledgeBaseServiceGrpc.KnowledgeBaseServiceBlockingStub kbStubWithDeadline() {
    return withDeadline(kbStub);
  }

  private <T extends io.grpc.stub.AbstractBlockingStub<T>> T withDeadline(T stub) {
    if (deadlineMillis > 0) {
      return stub.withDeadlineAfter(deadlineMillis, TimeUnit.MILLISECONDS);
    }
    return stub;
  }

  private static KnowledgeBaseResponse toResponse(KnowledgeBase kb, boolean apiKeyReady) {
    return new KnowledgeBaseResponse(
        kb.getKnowledgeBaseId(),
        kb.getTenantId(),
        kb.getName(),
        apiKeyReady,
        epochMillisToInstant(kb.getCreatedAtEpochMillis()),
        epochMillisToInstant(kb.getUpdatedAtEpochMillis()));
  }

  private static Instant epochMillisToInstant(long millis) {
    return millis <= 0 ? null : Instant.ofEpochMilli(millis);
  }

  private static long parseLong(String decimalId) {
    try {
      return Long.parseLong(decimalId);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("invalid decimal id: " + decimalId);
    }
  }

  private static RuntimeException mapAuthorize(StatusRuntimeException e) {
    Status.Code code = e.getStatus().getCode();
    if (code == Status.Code.PERMISSION_DENIED) {
      return new ForbiddenException();
    }
    if (code == Status.Code.NOT_FOUND) {
      // 跨租户统一 not found，不泄漏成员关系
      return new NotFoundException();
    }
    if (code == Status.Code.INVALID_ARGUMENT) {
      return new IllegalArgumentException("invalid authorize argument");
    }
    if (code == Status.Code.DEADLINE_EXCEEDED) {
      return new EnsureScopeFailedException.DownstreamTimeoutException();
    }
    log.warn("Access Authorize 下游失败 — code={} desc={}", code, e.getStatus().getDescription());
    return new EnsureScopeFailedException.DownstreamUnavailableException();
  }

  private static RuntimeException mapScope(StatusRuntimeException e) {
    Status.Code code = e.getStatus().getCode();
    if (code == Status.Code.NOT_FOUND) {
      return new NotFoundException();
    }
    if (code == Status.Code.PERMISSION_DENIED) {
      return new ForbiddenException();
    }
    if (code == Status.Code.INVALID_ARGUMENT) {
      return new IllegalArgumentException("invalid scope argument");
    }
    if (code == Status.Code.DEADLINE_EXCEEDED) {
      return new EnsureScopeFailedException.DownstreamTimeoutException();
    }
    log.warn("Access Scope 下游失败 — code={} desc={}", code, e.getStatus().getDescription());
    return new EnsureScopeFailedException.DownstreamUnavailableException();
  }

  private static RuntimeException mapKnowledgeBase(StatusRuntimeException e) {
    Status.Code code = e.getStatus().getCode();
    if (code == Status.Code.NOT_FOUND) {
      // 跨租户/不存在统一 not found
      return new NotFoundException();
    }
    if (code == Status.Code.PERMISSION_DENIED) {
      return new ForbiddenException();
    }
    if (code == Status.Code.ALREADY_EXISTS || code == Status.Code.FAILED_PRECONDITION) {
      return new ConflictException();
    }
    if (code == Status.Code.INVALID_ARGUMENT) {
      return new IllegalArgumentException("invalid knowledge base argument");
    }
    if (code == Status.Code.DEADLINE_EXCEEDED) {
      return new EnsureScopeFailedException.DownstreamTimeoutException();
    }
    log.warn("Knowledge KB 下游失败 — code={} desc={}", code, e.getStatus().getDescription());
    return new EnsureScopeFailedException.DownstreamUnavailableException();
  }

  /** 已认证但无建库/查看权限 → 403 FORBIDDEN。 */
  public static class ForbiddenException extends RuntimeException {
    public ForbiddenException() {
      super("forbidden");
    }
  }

  /** 跨租户或 KB 不存在 → 404 NOT_FOUND，不泄漏存在性。 */
  public static class NotFoundException extends RuntimeException {
    public NotFoundException() {
      super("not found");
    }
  }

  /** 建库冲突（同租户重名被拒绝等）→ 409 CONFLICT。 */
  public static class ConflictException extends RuntimeException {
    public ConflictException() {
      super("conflict");
    }
  }

  /** 下游 Access/Knowledge 不可用或超时的统一异常集合（plan_21/21.8）。 */
  public static class EnsureScopeFailedException {
    /** 下游不可用 → 503 DOWNSTREAM_UNAVAILABLE。 */
    public static class DownstreamUnavailableException extends RuntimeException {
      public DownstreamUnavailableException() {
        super("downstream unavailable");
      }
    }

    /** 下游超时 → 504 DOWNSTREAM_TIMEOUT。 */
    public static class DownstreamTimeoutException extends RuntimeException {
      public DownstreamTimeoutException() {
        super("downstream timeout");
      }
    }
  }
}
