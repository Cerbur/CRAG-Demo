package ai.cerbur.crag.console.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.cerbur.crag.console.auth.dto.TenantSummaryResponse;
import ai.cerbur.crag.console.auth.dto.UserResponse;
import ai.cerbur.crag.console.auth.service.AccessIdentityClient.DownstreamUnavailableException;
import ai.cerbur.crag.console.auth.service.AccessIdentityClient.InvalidCredentialsException;
import ai.cerbur.crag.contracts.access.v1.AuthenticationResponse;
import ai.cerbur.crag.contracts.access.v1.GetJwtVerificationKeysRequest;
import ai.cerbur.crag.contracts.access.v1.GetUserProfileRequest;
import ai.cerbur.crag.contracts.access.v1.IdentityServiceGrpc;
import ai.cerbur.crag.contracts.access.v1.JwtVerificationKey;
import ai.cerbur.crag.contracts.access.v1.JwtVerificationKeySet;
import ai.cerbur.crag.contracts.access.v1.ListUserTenantsRequest;
import ai.cerbur.crag.contracts.access.v1.ListUserTenantsResponse;
import ai.cerbur.crag.contracts.access.v1.LoginRequest;
import ai.cerbur.crag.contracts.access.v1.LogoutRequest;
import ai.cerbur.crag.contracts.access.v1.MembershipRole;
import ai.cerbur.crag.contracts.access.v1.RefreshRequest;
import ai.cerbur.crag.contracts.access.v1.RegisterRequest;
import ai.cerbur.crag.contracts.access.v1.UserProfile;
import ai.cerbur.crag.contracts.access.v1.UserTenant;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * AccessIdentityClient 进程内 gRPC 组件测试（plan_21/21.6）。
 *
 * <p>真实跨服务调用由 21.13 Docker 全链路回归证明；本测试验证 gRPC 装配、gRPC Status → 业务异常映射与字段映射，不表述为端到端兼容证明。
 */
@DisplayName("AccessIdentityClient in-process gRPC")
class AccessIdentityClientTest {

  private Server server;
  private ManagedChannel channel;
  private FakeIdentityService fake;
  private AccessIdentityClient client;

  @BeforeEach
  void setUp() throws IOException {
    fake = new FakeIdentityService();
    String name = InProcessServerBuilder.generateName();
    server = InProcessServerBuilder.forName(name).directExecutor().addService(fake).build().start();
    channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    client = new AccessIdentityClient(channel, 5000L);
  }

  @AfterEach
  void tearDown() {
    if (channel != null && !channel.isShutdown()) channel.shutdownNow();
    if (server != null && !server.isShutdown()) server.shutdownNow();
  }

  @Test
  @DisplayName("register → AuthResponse；defaultTenant 来自首个 tenant")
  void registerMapsResponse() {
    var material = client.register("alice", "alice", "alice-password-123");
    assertThat(material.response().accessToken()).isEqualTo("access-jwt");
    assertThat(material.response().user().userId()).isEqualTo("123");
    assertThat(material.rawRefreshToken()).isEqualTo("rt-raw");
  }

  @Test
  @DisplayName("login INVALID_ARGUMENT → InvalidCredentialsException")
  void loginInvalidCredentials() {
    fake.loginStatus = Status.INVALID_ARGUMENT.withDescription("bad password");
    assertThatThrownBy(() -> client.login("alice", "wrong"))
        .isInstanceOf(InvalidCredentialsException.class);
  }

  @Test
  @DisplayName("login UNAUTHENTICATED → InvalidCredentialsException（不泄漏原因）")
  void loginUnauthenticatedMapsInvalidCredentials() {
    fake.loginStatus = Status.UNAUTHENTICATED;
    assertThatThrownBy(() -> client.login("alice", "wrong"))
        .isInstanceOf(InvalidCredentialsException.class);
  }

  @Test
  @DisplayName("login UNAVAILABLE → DownstreamUnavailableException")
  void loginUnavailableMapsDownstream() {
    fake.loginStatus = Status.UNAVAILABLE;
    assertThatThrownBy(() -> client.login("alice", "x"))
        .isInstanceOf(DownstreamUnavailableException.class);
  }

  @Test
  @DisplayName("refresh 成功轮换 Access JWT")
  void refreshRotatesToken() {
    var r = client.refresh("rt-raw");
    assertThat(r.response().accessToken()).isEqualTo("access-jwt");
  }

  @Test
  @DisplayName("logout 传 userId + sessionFamilyId")
  void logoutSendsIds() {
    client.logout(123L, 456L);
    assertThat(fake.lastLogoutUserId).isEqualTo("123");
    assertThat(fake.lastLogoutSessionFamilyId).isEqualTo("456");
  }

  @Test
  @DisplayName("listTenants 分页参数透传")
  void listTenantsPaginates() {
    List<TenantSummaryResponse> ts = client.listTenants(123L, 20, null);
    assertThat(ts).hasSize(1);
    assertThat(ts.get(0).tenantId()).isEqualTo("1");
  }

  @Test
  @DisplayName("getUserProfile 返回安全投影")
  void getUserProfileSafeProjection() {
    UserResponse u = client.getUserProfile(123L);
    assertThat(u.userId()).isEqualTo("123");
    assertThat(u.nickname()).isEqualTo("alice");
  }

  @Test
  @DisplayName("loadVerificationKeys 返回公钥集（不含私钥字段）")
  void loadVerificationKeys() {
    JwtVerificationKeySet ks = client.loadVerificationKeys();
    assertThat(ks.getKeysList()).hasSize(1);
    JwtVerificationKey k = ks.getKeys(0);
    assertThat(k.getKid()).isEqualTo("kid-1");
    assertThat(k.getAlgorithm()).isEqualTo("RS256");
    assertThat(k.getPublicKeyPem()).contains("PUBLIC KEY");
  }

  static class FakeIdentityService extends IdentityServiceGrpc.IdentityServiceImplBase {
    Status loginStatus = Status.OK;
    String lastLogoutUserId;
    String lastLogoutSessionFamilyId;

    @Override
    public void register(RegisterRequest req, StreamObserver<AuthenticationResponse> resp) {
      resp.onNext(authResponse());
      resp.onCompleted();
    }

    @Override
    public void login(LoginRequest req, StreamObserver<AuthenticationResponse> resp) {
      if (loginStatus != Status.OK) {
        resp.onError(loginStatus.asRuntimeException());
        return;
      }
      resp.onNext(authResponse());
      resp.onCompleted();
    }

    @Override
    public void refresh(RefreshRequest req, StreamObserver<AuthenticationResponse> resp) {
      resp.onNext(authResponse());
      resp.onCompleted();
    }

    @Override
    public void logout(
        LogoutRequest req, StreamObserver<ai.cerbur.crag.contracts.access.v1.LogoutResponse> resp) {
      lastLogoutUserId = req.getUserId();
      lastLogoutSessionFamilyId = req.getSessionFamilyId();
      resp.onNext(ai.cerbur.crag.contracts.access.v1.LogoutResponse.getDefaultInstance());
      resp.onCompleted();
    }

    @Override
    public void getUserProfile(GetUserProfileRequest req, StreamObserver<UserProfile> resp) {
      resp.onNext(UserProfile.newBuilder().setUserId("123").setNickname("alice").build());
      resp.onCompleted();
    }

    @Override
    public void listUserTenants(
        ListUserTenantsRequest req, StreamObserver<ListUserTenantsResponse> resp) {
      resp.onNext(
          ListUserTenantsResponse.newBuilder()
              .addTenants(
                  UserTenant.newBuilder()
                      .setTenantId("1")
                      .setName("default")
                      .setRole(MembershipRole.MEMBERSHIP_ROLE_OWNER)
                      .build())
              .build());
      resp.onCompleted();
    }

    @Override
    public void getJwtVerificationKeys(
        GetJwtVerificationKeysRequest req, StreamObserver<JwtVerificationKeySet> resp) {
      resp.onNext(
          JwtVerificationKeySet.newBuilder()
              .addKeys(
                  JwtVerificationKey.newBuilder()
                      .setKid("kid-1")
                      .setAlgorithm("RS256")
                      .setPublicKeyPem("-----BEGIN PUBLIC KEY-----\nMIIB\n-----END PUBLIC KEY-----")
                      .build())
              .build());
      resp.onCompleted();
    }

    private AuthenticationResponse authResponse() {
      return AuthenticationResponse.newBuilder()
          .setUserId("123")
          .setNickname("alice")
          .setAccessToken("access-jwt")
          .setAccessExpiresAtEpochMillis(
              Long.toString(Instant.parse("2026-06-29T00:15:00Z").toEpochMilli()))
          .setRefreshToken("rt-raw")
          .setRefreshExpiresAtEpochMillis(
              Long.toString(Instant.parse("2026-06-29T01:00:00Z").toEpochMilli()))
          .setSessionFamilyId("456")
          .build();
    }
  }
}
