package ai.cerbur.crag.console.apikey.service;

import ai.cerbur.crag.console.apikey.dto.ApiKeyListResponse;
import ai.cerbur.crag.console.apikey.dto.ApiKeyResponse;
import ai.cerbur.crag.console.apikey.dto.CreatedApiKeyResponse;
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
 * Console API Key 编排适配器（plan_21/21.9）。
 *
 * <p>每个 operation 先 Authorize({@link TenantAction#TENANT_MANAGE_API_KEY}) 验证 KB
 * 归属（OWNER-only；MEMBER 越权 返回 PERMISSION_DENIED → 403，跨租户/KB 不存在统一 NOT_FOUND 不泄漏存在性），再 EnsureScope
 * 兜底（Access 消费者会通过 {@code KNOWLEDGE_BASE_CREATED} 补齐 Scope，但 Key 命令前仍兜底一次，幂等），最后调用 Access Key gRPC。
 *
 * <p>完整 Key（{@code completeKey}）只出现在 {@link CreatedApiKeyResponse}（create/rotate
 * 响应）；list/get/disable/ enable/revoke 只返回 {@link ApiKeyResponse}（前缀投影）。{@link
 * CreatedApiKeyResponse#toString()} 屏蔽完整 Key，日志与 异常 message 一律不含完整 Key。
 *
 * <p>actor userId 只来自 ConsolePrincipal；KB/tenant 来自路径参数；非幂等 create/rotate/revoke 不自动重试。状态冲突
 * （disable 已 DISABLED、enable 非 DISABLED、revoke 已 REVOKED、rotate DISABLED/REVOKED）→ Access 抛
 * FAILED_PRECONDITION → 映射 409。所有 gRPC/HTTP ID 使用十进制字符串。
 */
@Component
public class ApiKeyOrchestrator {

  private static final Logger log = LoggerFactory.getLogger(ApiKeyOrchestrator.class);

  private final MembershipServiceGrpc.MembershipServiceBlockingStub membershipStub;
  private final ApiKeyServiceGrpc.ApiKeyServiceBlockingStub apiKeyStub;
  private final long deadlineMillis;

  @Autowired
  public ApiKeyOrchestrator(
      @Qualifier("consoleAccessChannel") ManagedChannel accessChannel,
      @Value("${crag.grpc.client.max-deadline-millis:10000}") long deadlineMillis) {
    this.membershipStub = MembershipServiceGrpc.newBlockingStub(accessChannel);
    this.apiKeyStub = ApiKeyServiceGrpc.newBlockingStub(accessChannel);
    this.deadlineMillis = deadlineMillis;
  }

  /** 列表：Authorize MANAGE_API_KEY → EnsureScope 兜底 → Access ListApiKeys（前缀投影）。 */
  public ApiKeyListResponse list(
      long actorUserId, long tenantId, long knowledgeBaseId, int pageSize, String pageToken) {
    authorize(actorUserId, tenantId, knowledgeBaseId);
    ensureScopeQuiet(actorUserId, tenantId, knowledgeBaseId);
    try {
      ListApiKeysResponse resp =
          apiKeyStubWithDeadline()
              .listApiKeys(
                  ListApiKeysRequest.newBuilder()
                      .setActorUserId(Long.toString(actorUserId))
                      .setTenantId(Long.toString(tenantId))
                      .setKnowledgeBaseId(Long.toString(knowledgeBaseId))
                      .setPageSize(pageSize)
                      .setPageToken(pageToken == null ? "" : pageToken)
                      .build());
      List<ApiKeyResponse> items = new ArrayList<>();
      for (ApiKeyView v : resp.getApiKeysList()) {
        items.add(toView(v));
      }
      String next = resp.getNextPageToken();
      return new ApiKeyListResponse(items, next == null || next.isEmpty() ? null : next);
    } catch (StatusRuntimeException e) {
      throw mapKey(e);
    }
  }

  /** 详情：Authorize → EnsureScope → Access GetApiKey（前缀投影）。 */
  public ApiKeyResponse get(long actorUserId, long tenantId, long knowledgeBaseId, long apiKeyId) {
    authorize(actorUserId, tenantId, knowledgeBaseId);
    ensureScopeQuiet(actorUserId, tenantId, knowledgeBaseId);
    try {
      ApiKeyView v =
          apiKeyStubWithDeadline()
              .getApiKey(
                  GetApiKeyRequest.newBuilder()
                      .setActorUserId(Long.toString(actorUserId))
                      .setTenantId(Long.toString(tenantId))
                      .setApiKeyId(Long.toString(apiKeyId))
                      .build());
      return toView(v);
    } catch (StatusRuntimeException e) {
      throw mapKey(e);
    }
  }

  /** 创建：Authorize → EnsureScope → Access CreateApiKey（返回一次性 completeKey）。 */
  public CreatedApiKeyResponse create(
      long actorUserId, long tenantId, long knowledgeBaseId, String name, long ttlSeconds) {
    authorize(actorUserId, tenantId, knowledgeBaseId);
    ensureScopeQuiet(actorUserId, tenantId, knowledgeBaseId);
    CreateApiKeyRequest.Builder req =
        CreateApiKeyRequest.newBuilder()
            .setActorUserId(Long.toString(actorUserId))
            .setTenantId(Long.toString(tenantId))
            .setKnowledgeBaseId(Long.toString(knowledgeBaseId))
            .setName(name);
    if (ttlSeconds > 0) {
      req.setTtlSeconds(ttlSeconds);
    }
    try {
      CreatedApiKey created = apiKeyStubWithDeadline().createApiKey(req.build());
      return toCreated(created);
    } catch (StatusRuntimeException e) {
      throw mapKey(e);
    }
  }

  /** 停用：Authorize → EnsureScope → Access DisableApiKey。状态冲突（非 ACTIVE）→ 409。 */
  public ApiKeyResponse disable(
      long actorUserId, long tenantId, long knowledgeBaseId, long apiKeyId) {
    return changeState(actorUserId, tenantId, knowledgeBaseId, apiKeyId, StateChange.DISABLE);
  }

  /** 启用：Authorize → EnsureScope → Access EnableApiKey。状态冲突（非 DISABLED）→ 409。 */
  public ApiKeyResponse enable(
      long actorUserId, long tenantId, long knowledgeBaseId, long apiKeyId) {
    return changeState(actorUserId, tenantId, knowledgeBaseId, apiKeyId, StateChange.ENABLE);
  }

  /** 吊销：Authorize → EnsureScope → Access RevokeApiKey。状态冲突（已 REVOKED）→ 409。 */
  public ApiKeyResponse revoke(
      long actorUserId, long tenantId, long knowledgeBaseId, long apiKeyId) {
    return changeState(actorUserId, tenantId, knowledgeBaseId, apiKeyId, StateChange.REVOKE);
  }

  /** 轮换：Authorize → EnsureScope → Access RotateApiKey（返回一次性新 completeKey）。状态冲突 → 409。 */
  public CreatedApiKeyResponse rotate(
      long actorUserId, long tenantId, long knowledgeBaseId, long apiKeyId, long ttlSeconds) {
    authorize(actorUserId, tenantId, knowledgeBaseId);
    ensureScopeQuiet(actorUserId, tenantId, knowledgeBaseId);
    RotateApiKeyRequest.Builder req =
        RotateApiKeyRequest.newBuilder()
            .setActorUserId(Long.toString(actorUserId))
            .setTenantId(Long.toString(tenantId))
            .setApiKeyId(Long.toString(apiKeyId));
    if (ttlSeconds > 0) {
      req.setTtlSeconds(ttlSeconds);
    }
    try {
      CreatedApiKey created = apiKeyStubWithDeadline().rotateApiKey(req.build());
      return toCreated(created);
    } catch (StatusRuntimeException e) {
      throw mapKey(e);
    }
  }

  // ---- helpers ----

  private ApiKeyResponse changeState(
      long actorUserId, long tenantId, long knowledgeBaseId, long apiKeyId, StateChange change) {
    authorize(actorUserId, tenantId, knowledgeBaseId);
    ensureScopeQuiet(actorUserId, tenantId, knowledgeBaseId);
    ChangeApiKeyStateRequest req =
        ChangeApiKeyStateRequest.newBuilder()
            .setActorUserId(Long.toString(actorUserId))
            .setTenantId(Long.toString(tenantId))
            .setApiKeyId(Long.toString(apiKeyId))
            .build();
    try {
      ApiKeyView v =
          switch (change) {
            case DISABLE -> apiKeyStubWithDeadline().disableApiKey(req);
            case ENABLE -> apiKeyStubWithDeadline().enableApiKey(req);
            case REVOKE -> apiKeyStubWithDeadline().revokeApiKey(req);
          };
      return toView(v);
    } catch (StatusRuntimeException e) {
      throw mapKey(e);
    }
  }

  /** Authorize MANAGE_API_KEY：拒绝 → ForbiddenException；跨租户 NOT_FOUND → NotFoundException。 */
  private void authorize(long actorUserId, long tenantId, long knowledgeBaseId) {
    try {
      AuthorizationDecision decision =
          membershipStubWithDeadline()
              .authorizeTenantAction(
                  AuthorizeTenantActionRequest.newBuilder()
                      .setActorUserId(Long.toString(actorUserId))
                      .setTenantId(Long.toString(tenantId))
                      .setAction(TenantAction.TENANT_MANAGE_API_KEY)
                      .build());
      if (!decision.getAllowed()) {
        throw new ForbiddenException();
      }
    } catch (StatusRuntimeException e) {
      throw mapAuthorize(e);
    }
  }

  /**
   * EnsureScope 兜底：Scope
   * 暂时未就绪（UNAVAILABLE/FAILED_PRECONDITION/ALREADY_EXISTS/ABORTED/DEADLINE_EXCEEDED） 降级为不阻断 Key
   * 命令（不抛异常）；其余错误按异常映射。返回值未使用，仅用于触发幂等 ensure。
   */
  @SuppressWarnings("unused")
  private void ensureScopeQuiet(long actorUserId, long tenantId, long knowledgeBaseId) {
    try {
      apiKeyStubWithDeadline()
          .ensureScope(
              EnsureScopeRequest.newBuilder()
                  .setActorUserId(Long.toString(actorUserId))
                  .setTenantId(Long.toString(tenantId))
                  .setKnowledgeBaseId(Long.toString(knowledgeBaseId))
                  .build());
    } catch (StatusRuntimeException e) {
      Status.Code code = e.getStatus().getCode();
      if (code == Status.Code.UNAVAILABLE
          || code == Status.Code.DEADLINE_EXCEEDED
          || code == Status.Code.ABORTED
          || code == Status.Code.FAILED_PRECONDITION
          || code == Status.Code.ALREADY_EXISTS) {
        // Scope 暂时未就绪，降级；Key 命令仍可继续，Access 消费者会补齐
        log.debug("EnsureScope 降级（Key 命令继续）— kb={} code={}", knowledgeBaseId, code);
        return;
      }
      // 其余错误（NOT_FOUND/PERMISSION_DENIED/INVALID_ARGUMENT/INTERNAL）按异常映射
      throw mapKey(e);
    }
  }

  private MembershipServiceGrpc.MembershipServiceBlockingStub membershipStubWithDeadline() {
    return withDeadline(membershipStub);
  }

  private ApiKeyServiceGrpc.ApiKeyServiceBlockingStub apiKeyStubWithDeadline() {
    return withDeadline(apiKeyStub);
  }

  private <T extends io.grpc.stub.AbstractBlockingStub<T>> T withDeadline(T stub) {
    if (deadlineMillis > 0) {
      return stub.withDeadlineAfter(deadlineMillis, TimeUnit.MILLISECONDS);
    }
    return stub;
  }

  private static ApiKeyResponse toView(ApiKeyView v) {
    return new ApiKeyResponse(
        v.getApiKeyId(),
        v.getKnowledgeBaseId(),
        v.getName(),
        statusToString(v.getStatus()),
        v.getKeyPrefix(),
        epochMillisToInstant(v.getCreatedAtEpochMillis()),
        epochMillisToInstant(v.getExpiresAtEpochMillis()));
  }

  private static CreatedApiKeyResponse toCreated(CreatedApiKey c) {
    return new CreatedApiKeyResponse(
        c.getApiKeyId(),
        c.getKnowledgeBaseId(),
        c.getName(),
        c.getCompleteKey(),
        epochMillisToInstant(c.getExpiresAtEpochMillis()));
  }

  private static String statusToString(ApiKeyStatus status) {
    return switch (status) {
      case KEY_ACTIVE -> "ACTIVE";
      case KEY_DISABLED -> "DISABLED";
      case KEY_REVOKED -> "REVOKED";
      case KEY_EXPIRED -> "EXPIRED";
      case API_KEY_STATUS_UNSPECIFIED, UNRECOGNIZED -> "ACTIVE";
    };
  }

  private static Instant epochMillisToInstant(String decimalMillis) {
    if (decimalMillis == null || decimalMillis.isEmpty()) {
      return null;
    }
    try {
      long millis = Long.parseLong(decimalMillis);
      return millis <= 0 ? null : Instant.ofEpochMilli(millis);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static RuntimeException mapAuthorize(StatusRuntimeException e) {
    Status.Code code = e.getStatus().getCode();
    if (code == Status.Code.PERMISSION_DENIED) {
      return new ForbiddenException();
    }
    if (code == Status.Code.NOT_FOUND) {
      // 跨租户/KB 不存在统一 not found，不泄漏存在性
      return new NotFoundException();
    }
    if (code == Status.Code.INVALID_ARGUMENT) {
      return new IllegalArgumentException("invalid authorize argument");
    }
    if (code == Status.Code.DEADLINE_EXCEEDED) {
      return new DownstreamTimeoutException();
    }
    return new DownstreamUnavailableException();
  }

  private static RuntimeException mapKey(StatusRuntimeException e) {
    Status.Code code = e.getStatus().getCode();
    if (code == Status.Code.PERMISSION_DENIED) {
      // MEMBER 无权管理或 actor 非 OWNER → 403
      return new ForbiddenException();
    }
    if (code == Status.Code.NOT_FOUND) {
      // 跨 KB / Key 不存在统一 not found，不泄漏存在性
      return new NotFoundException();
    }
    if (code == Status.Code.FAILED_PRECONDITION
        || code == Status.Code.ALREADY_EXISTS
        || code == Status.Code.ABORTED) {
      // 状态冲突（disable 已 DISABLED、enable 非 DISABLED、revoke 已 REVOKED、rotate DISABLED/REVOKED）→ 409
      return new ConflictException();
    }
    if (code == Status.Code.INVALID_ARGUMENT) {
      return new IllegalArgumentException("invalid api key argument");
    }
    if (code == Status.Code.DEADLINE_EXCEEDED) {
      return new DownstreamTimeoutException();
    }
    log.warn("Access ApiKey 下游失败 — code={} desc={}", code, e.getStatus().getDescription());
    return new DownstreamUnavailableException();
  }

  private enum StateChange {
    DISABLE,
    ENABLE,
    REVOKE
  }

  /** MEMBER 无权管理或 actor 非有效 OWNER → 403 FORBIDDEN。 */
  public static class ForbiddenException extends RuntimeException {
    public ForbiddenException() {
      super("forbidden");
    }
  }

  /** 跨租户/KB 或 Key 不存在 → 404 NOT_FOUND，不泄漏存在性。 */
  public static class NotFoundException extends RuntimeException {
    public NotFoundException() {
      super("not found");
    }
  }

  /** 状态冲突（disable 已 DISABLED、revoke 已 REVOKED、rotate 非 ACTIVE）→ 409 CONFLICT。 */
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
