package ai.cerbur.crag.console.auth.service;

import ai.cerbur.crag.console.auth.dto.AuthResponse;
import ai.cerbur.crag.console.auth.dto.TenantSummaryResponse;
import ai.cerbur.crag.console.auth.dto.UserResponse;
import ai.cerbur.crag.contracts.access.v1.AuthenticationResponse;
import ai.cerbur.crag.contracts.access.v1.GetJwtVerificationKeysRequest;
import ai.cerbur.crag.contracts.access.v1.GetUserProfileRequest;
import ai.cerbur.crag.contracts.access.v1.IdentityServiceGrpc;
import ai.cerbur.crag.contracts.access.v1.JwtVerificationKeySet;
import ai.cerbur.crag.contracts.access.v1.ListUserTenantsRequest;
import ai.cerbur.crag.contracts.access.v1.ListUserTenantsResponse;
import ai.cerbur.crag.contracts.access.v1.LoginRequest;
import ai.cerbur.crag.contracts.access.v1.LogoutRequest;
import ai.cerbur.crag.contracts.access.v1.RefreshRequest;
import ai.cerbur.crag.contracts.access.v1.RegisterRequest;
import ai.cerbur.crag.contracts.access.v1.UserProfile;
import ai.cerbur.crag.contracts.access.v1.UserTenant;
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
 * Access Identity gRPC 适配器（plan_21/21.6）。
 *
 * <p>封装 register/login/refresh/logout/getUserProfile/listUserTenants/loadVerificationKeys 的 gRPC
 * 调用，并将稳定 gRPC Status 映射为 Console 业务异常。不泄漏登录/Refresh 失败的具体原因。非幂等
 * RPC（register/logout/refresh/login）不自动重试；deadline 由 per-call 配置。
 */
@Component
public class AccessIdentityClient {

  private static final Logger log = LoggerFactory.getLogger(AccessIdentityClient.class);

  private final ManagedChannel channel;
  private final IdentityServiceGrpc.IdentityServiceBlockingStub stub;
  private final long deadlineMillis;

  /**
   * 构造时注入 Access channel Bean 与 per-call deadline。
   *
   * <p>测试可注入进程内 channel；Spring 注入 {@code consoleAccessChannel} Bean（由 {@code
   * ConsoleGrpcClientConfiguration} 创建）。
   */
  @Autowired
  public AccessIdentityClient(
      @Qualifier("consoleAccessChannel") ManagedChannel channel,
      @Value("${crag.grpc.client.max-deadline-millis:10000}") long deadlineMillis) {
    this.channel = channel;
    this.stub = IdentityServiceGrpc.newBlockingStub(channel);
    this.deadlineMillis = deadlineMillis;
  }

  public TokenMaterial register(String nickname, String username, String password) {
    AuthenticationResponse r =
        stub()
            .register(
                RegisterRequest.newBuilder()
                    .setNickname(nickname)
                    .setUsername(username)
                    .setPassword(password)
                    .build());
    return new TokenMaterial(toAuthResponse(r, true), r.getRefreshToken());
  }

  public TokenMaterial login(String username, String password) {
    try {
      AuthenticationResponse r =
          stub()
              .login(LoginRequest.newBuilder().setUsername(username).setPassword(password).build());
      return new TokenMaterial(toAuthResponse(r, false), r.getRefreshToken());
    } catch (StatusRuntimeException e) {
      throw mapLogin(e);
    }
  }

  public TokenMaterial refresh(String rawRefreshToken) {
    try {
      AuthenticationResponse r =
          stub().refresh(RefreshRequest.newBuilder().setRefreshToken(rawRefreshToken).build());
      return new TokenMaterial(toAuthResponse(r, false), r.getRefreshToken());
    } catch (StatusRuntimeException e) {
      throw mapLogin(e);
    }
  }

  public void logout(long userId, long sessionFamilyId) {
    try {
      stub()
          .logout(
              LogoutRequest.newBuilder()
                  .setUserId(Long.toString(userId))
                  .setSessionFamilyId(Long.toString(sessionFamilyId))
                  .build());
    } catch (StatusRuntimeException e) {
      throw mapDownstream(e);
    }
  }

  public UserResponse getUserProfile(long userId) {
    try {
      UserProfile p =
          stub()
              .getUserProfile(
                  GetUserProfileRequest.newBuilder().setUserId(Long.toString(userId)).build());
      return new UserResponse(p.getUserId(), p.getNickname());
    } catch (StatusRuntimeException e) {
      throw mapDownstream(e);
    }
  }

  public List<TenantSummaryResponse> listTenants(long userId, int pageSize, String pageToken) {
    return listTenantsPage(userId, pageSize, pageToken).items();
  }

  /**
   * 列出当前用户有效 Tenant 与角色，并保留 nextPageToken（plan_21/21.7）。
   *
   * <p>pageToken 为 tenantId 游标（由 Access 返回），保证分页稳定。
   */
  public TenantsPage listTenantsPage(long userId, int pageSize, String pageToken) {
    try {
      ListUserTenantsResponse resp =
          stub()
              .listUserTenants(
                  ListUserTenantsRequest.newBuilder()
                      .setUserId(Long.toString(userId))
                      .setPageSize(pageSize)
                      .setPageToken(pageToken == null ? "" : pageToken)
                      .build());
      List<TenantSummaryResponse> out = new ArrayList<>();
      for (UserTenant t : resp.getTenantsList()) {
        out.add(new TenantSummaryResponse(t.getTenantId(), t.getName(), roleToString(t.getRole())));
      }
      String next = resp.getNextPageToken();
      return new TenantsPage(out, next == null || next.isEmpty() ? null : next);
    } catch (StatusRuntimeException e) {
      throw mapDownstream(e);
    }
  }

  /** Tenant 列表分页结果（plan_21/21.7）。items + nextPageToken（tenantId 游标）。 */
  public record TenantsPage(List<TenantSummaryResponse> items, String nextPageToken) {
    public TenantsPage {
      items = items == null ? java.util.List.of() : java.util.List.copyOf(items);
    }
  }

  public JwtVerificationKeySet loadVerificationKeys() {
    try {
      return stub().getJwtVerificationKeys(GetJwtVerificationKeysRequest.getDefaultInstance());
    } catch (StatusRuntimeException e) {
      throw mapDownstream(e);
    }
  }

  // ---- helpers ----

  private IdentityServiceGrpc.IdentityServiceBlockingStub stub() {
    if (deadlineMillis > 0) {
      return stub.withDeadlineAfter(deadlineMillis, TimeUnit.MILLISECONDS);
    }
    return stub;
  }

  private static AuthResponse toAuthResponse(AuthenticationResponse r, boolean register) {
    Instant expiresAt = parseInstantMillis(r.getAccessExpiresAtEpochMillis());
    UserResponse user = new UserResponse(r.getUserId(), r.getNickname());
    TenantSummaryResponse defaultTenant = null;
    if (register) {
      // default tenant 由 Console 在 register 后续从 listTenants 取首个；此处仅 placeholder
      defaultTenant = null;
    }
    return new AuthResponse(r.getAccessToken(), expiresAt, user, defaultTenant);
  }

  private static RuntimeException mapLogin(StatusRuntimeException e) {
    Status.Code code = e.getStatus().getCode();
    if (code == Status.Code.INVALID_ARGUMENT
        || code == Status.Code.UNAUTHENTICATED
        || code == Status.Code.NOT_FOUND) {
      log.debug("Access 登录/刷新拒绝 — code={}", code);
      return new InvalidCredentialsException();
    }
    return mapDownstream(e);
  }

  private static RuntimeException mapDownstream(StatusRuntimeException e) {
    Status.Code code = e.getStatus().getCode();
    if (code == Status.Code.DEADLINE_EXCEEDED) {
      return new DownstreamTimeoutException();
    }
    log.warn("Access 下游调用失败 — code={} desc={}", code, e.getStatus().getDescription());
    return new DownstreamUnavailableException();
  }

  private static Instant parseInstantMillis(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      return Instant.ofEpochMilli(Long.parseLong(value.trim()));
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static String roleToString(ai.cerbur.crag.contracts.access.v1.MembershipRole role) {
    return role.name();
  }

  /** 注册/登录/刷新的返回材料：可序列化给客户端的 AuthResponse + 仅写 Cookie 的 raw Refresh Token. */
  public record TokenMaterial(AuthResponse response, String rawRefreshToken) {}

  /** 登录/Refresh 凭据无效；映射为 40102，不泄漏原因. */
  public static class InvalidCredentialsException extends RuntimeException {
    public InvalidCredentialsException() {
      super("invalid credentials");
    }
  }

  /** 下游 Access 不可用；映射为 50301. */
  public static class DownstreamUnavailableException extends RuntimeException {
    public DownstreamUnavailableException() {
      super("downstream unavailable");
    }
  }

  /** 下游 Access 超时；映射为 50401. */
  public static class DownstreamTimeoutException extends RuntimeException {
    public DownstreamTimeoutException() {
      super("downstream timeout");
    }
  }
}
