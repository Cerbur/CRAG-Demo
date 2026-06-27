package ai.cerbur.crag.access.controller.smoke;

import ai.cerbur.crag.access.controller.smoke.dto.AccessSmokeRequests;
import ai.cerbur.crag.access.controller.smoke.dto.AccessSmokeResponses;
import ai.cerbur.crag.access.core.apikey.ApiKeyResult;
import ai.cerbur.crag.access.core.apikey.ApiKeyScopeResult;
import ai.cerbur.crag.access.core.apikey.ApiKeyService;
import ai.cerbur.crag.access.core.apikey.AuthenticatedApiKey;
import ai.cerbur.crag.access.core.apikey.CreatedApiKey;
import ai.cerbur.crag.access.core.identity.RegisterIdentityCommand;
import ai.cerbur.crag.access.core.membership.MembershipResult;
import ai.cerbur.crag.access.core.membership.MembershipRole;
import ai.cerbur.crag.access.core.membership.MembershipService;
import ai.cerbur.crag.access.core.session.AuthenticationResult;
import ai.cerbur.crag.access.core.session.AuthenticationService;
import ai.cerbur.crag.access.core.session.JwtIssuer;
import ai.cerbur.crag.common.dto.result.Response;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * smoke-only Access HTTP 验收入口（{@code smoke} Profile），根路径 {@code /api/v1/smoke/access}。复用 Core
 * 用例服务，证明身份、会话、 Membership、API Key 与 JWT 公钥的真实链路。默认 Profile 不装配。
 */
@RestController
@Profile("smoke")
@RequestMapping("/api/v1/smoke/access")
public class AccessSmokeController {

  @Autowired private AuthenticationService authenticationService;
  @Autowired private MembershipService membershipService;
  @Autowired private ApiKeyService apiKeyService;
  @Autowired private JwtIssuer jwtIssuer;

  @PostMapping("/register")
  public Response<AccessSmokeResponses.AuthResponse> register(
      @RequestBody @Valid AccessSmokeRequests.RegisterRequest request) {
    return Response.success(
        toAuth(
            authenticationService.register(
                new RegisterIdentityCommand(
                    request.nickname(), request.username(), request.password().toCharArray()))));
  }

  @PostMapping("/login")
  public Response<AccessSmokeResponses.AuthResponse> login(
      @RequestBody @Valid AccessSmokeRequests.LoginRequest request) {
    return Response.success(
        toAuth(authenticationService.login(request.username(), request.password().toCharArray())));
  }

  @PostMapping("/refresh")
  public Response<AccessSmokeResponses.AuthResponse> refresh(
      @RequestBody @Valid AccessSmokeRequests.RefreshRequest request) {
    return Response.success(toAuth(authenticationService.refresh(request.refreshToken())));
  }

  @PostMapping("/logout")
  public Response<Object> logout(@RequestBody @Valid AccessSmokeRequests.LogoutRequest request) {
    authenticationService.logout(
        Long.parseLong(request.userId()), Long.parseLong(request.sessionFamilyId()));
    return Response.success(null);
  }

  @GetMapping("/jwt-keys")
  public Response<AccessSmokeResponses.JwtKeysResponse> jwtKeys() {
    List<AccessSmokeResponses.JwtKey> keys =
        jwtIssuer.verificationKeys().keys().stream()
            .map(k -> new AccessSmokeResponses.JwtKey(k.kid(), k.algorithm(), k.publicKeyPem()))
            .toList();
    return Response.success(new AccessSmokeResponses.JwtKeysResponse(keys));
  }

  @PostMapping("/memberships/add")
  public Response<AccessSmokeResponses.MembershipView> addMember(
      @RequestBody @Valid AccessSmokeRequests.AddMemberRequest request) {
    return Response.success(
        toMembership(
            membershipService.addByUsername(
                Long.parseLong(request.actorUserId()),
                Long.parseLong(request.tenantId()),
                request.username())));
  }

  @PostMapping("/memberships/{memberUserId}/role")
  public Response<AccessSmokeResponses.MembershipView> changeRole(
      @PathVariable String memberUserId,
      @RequestBody @Valid AccessSmokeRequests.ChangeRoleRequest request) {
    return Response.success(
        toMembership(
            membershipService.changeRole(
                Long.parseLong(request.actorUserId()),
                Long.parseLong(request.tenantId()),
                Long.parseLong(memberUserId),
                MembershipRole.valueOf(request.role()))));
  }

  @PostMapping("/memberships/{memberUserId}/remove")
  public Response<AccessSmokeResponses.MembershipView> removeMember(
      @PathVariable String memberUserId,
      @RequestBody @Valid AccessSmokeRequests.ActorTenant request) {
    return Response.success(
        toMembership(
            membershipService.remove(
                Long.parseLong(request.actorUserId()),
                Long.parseLong(request.tenantId()),
                Long.parseLong(memberUserId))));
  }

  @GetMapping("/memberships/{memberUserId}")
  public Response<AccessSmokeResponses.MembershipView> getMembership(
      @PathVariable String memberUserId,
      @RequestParam String actorUserId,
      @RequestParam String tenantId) {
    return Response.success(
        toMembership(
            membershipService.get(
                Long.parseLong(actorUserId),
                Long.parseLong(tenantId),
                Long.parseLong(memberUserId))));
  }

  @GetMapping("/memberships")
  public Response<List<AccessSmokeResponses.MembershipView>> listMemberships(
      @RequestParam String actorUserId, @RequestParam String tenantId) {
    List<AccessSmokeResponses.MembershipView> rows =
        membershipService
            .list(Long.parseLong(actorUserId), Long.parseLong(tenantId), 200, null)
            .stream()
            .map(AccessSmokeController::toMembership)
            .toList();
    return Response.success(rows);
  }

  @PostMapping("/scopes")
  public Response<AccessSmokeResponses.ScopeView> registerScope(
      @RequestBody @Valid AccessSmokeRequests.RegisterScopeRequest request) {
    return Response.success(
        toScope(
            apiKeyService.registerScope(
                Long.parseLong(request.actorUserId()),
                Long.parseLong(request.tenantId()),
                Long.parseLong(request.knowledgeBaseId()))));
  }

  @PostMapping("/scopes/{knowledgeBaseId}/block")
  public Response<AccessSmokeResponses.ScopeView> blockScope(
      @PathVariable String knowledgeBaseId,
      @RequestBody @Valid AccessSmokeRequests.ActorTenant request) {
    return Response.success(
        toScope(
            apiKeyService.blockScope(
                Long.parseLong(request.actorUserId()),
                Long.parseLong(request.tenantId()),
                Long.parseLong(knowledgeBaseId))));
  }

  @PostMapping("/api-keys")
  public Response<AccessSmokeResponses.CreatedKeyView> createApiKey(
      @RequestBody @Valid AccessSmokeRequests.CreateApiKeyRequest request) {
    return Response.success(
        toCreatedKey(
            apiKeyService.create(
                Long.parseLong(request.actorUserId()),
                Long.parseLong(request.tenantId()),
                Long.parseLong(request.knowledgeBaseId()),
                request.name(),
                request.ttlSeconds() == null ? null : Duration.ofSeconds(request.ttlSeconds()))));
  }

  @PostMapping("/api-keys/{apiKeyId}/disable")
  public Response<AccessSmokeResponses.KeyView> disableApiKey(
      @PathVariable String apiKeyId, @RequestBody @Valid AccessSmokeRequests.ActorTenant request) {
    return Response.success(
        toKeyView(
            apiKeyService.disable(
                Long.parseLong(request.actorUserId()),
                Long.parseLong(request.tenantId()),
                Long.parseLong(apiKeyId))));
  }

  @PostMapping("/api-keys/{apiKeyId}/enable")
  public Response<AccessSmokeResponses.KeyView> enableApiKey(
      @PathVariable String apiKeyId, @RequestBody @Valid AccessSmokeRequests.ActorTenant request) {
    return Response.success(
        toKeyView(
            apiKeyService.enable(
                Long.parseLong(request.actorUserId()),
                Long.parseLong(request.tenantId()),
                Long.parseLong(apiKeyId))));
  }

  @PostMapping("/api-keys/{apiKeyId}/rotate")
  public Response<AccessSmokeResponses.CreatedKeyView> rotateApiKey(
      @PathVariable String apiKeyId,
      @RequestBody @Valid AccessSmokeRequests.RotateApiKeyRequest request) {
    return Response.success(
        toCreatedKey(
            apiKeyService.rotate(
                Long.parseLong(request.actorUserId()),
                Long.parseLong(request.tenantId()),
                Long.parseLong(apiKeyId),
                request.ttlSeconds() == null ? null : Duration.ofSeconds(request.ttlSeconds()))));
  }

  @PostMapping("/api-keys/{apiKeyId}/revoke")
  public Response<AccessSmokeResponses.KeyView> revokeApiKey(
      @PathVariable String apiKeyId, @RequestBody @Valid AccessSmokeRequests.ActorTenant request) {
    return Response.success(
        toKeyView(
            apiKeyService.revoke(
                Long.parseLong(request.actorUserId()),
                Long.parseLong(request.tenantId()),
                Long.parseLong(apiKeyId))));
  }

  @PostMapping("/api-keys/authenticate")
  public Response<AccessSmokeResponses.AuthenticatedKeyView> authenticateApiKey(
      @RequestBody @Valid AccessSmokeRequests.AuthenticateApiKeyRequest request) {
    return Response.success(toAuthKey(apiKeyService.authenticate(request.apiKey())));
  }

  private static AccessSmokeResponses.AuthResponse toAuth(AuthenticationResult result) {
    return new AccessSmokeResponses.AuthResponse(
        Long.toString(result.userId()),
        result.nickname(),
        Long.toString(result.tenantId()),
        result.tokens().accessToken(),
        result.tokens().refreshToken(),
        Long.toString(result.tokens().sessionFamilyId()));
  }

  private static AccessSmokeResponses.MembershipView toMembership(MembershipResult result) {
    return new AccessSmokeResponses.MembershipView(
        Long.toString(result.membershipId()),
        Long.toString(result.tenantId()),
        Long.toString(result.userId()),
        result.role().name(),
        result.status(),
        result.version());
  }

  private static AccessSmokeResponses.ScopeView toScope(ApiKeyScopeResult result) {
    return new AccessSmokeResponses.ScopeView(
        Long.toString(result.knowledgeBaseId()),
        Long.toString(result.tenantId()),
        result.status(),
        result.version());
  }

  private static AccessSmokeResponses.CreatedKeyView toCreatedKey(CreatedApiKey result) {
    return new AccessSmokeResponses.CreatedKeyView(
        Long.toString(result.apiKeyId()),
        Long.toString(result.tenantId()),
        Long.toString(result.knowledgeBaseId()),
        result.name(),
        result.completeKey(),
        Long.toString(result.expiresAt().toEpochMilli()));
  }

  private static AccessSmokeResponses.KeyView toKeyView(ApiKeyResult result) {
    return new AccessSmokeResponses.KeyView(
        Long.toString(result.apiKeyId()),
        Long.toString(result.tenantId()),
        Long.toString(result.knowledgeBaseId()),
        result.name(),
        result.status(),
        result.keyPrefix(),
        Long.toString(result.expiresAt().toEpochMilli()),
        result.version());
  }

  private static AccessSmokeResponses.AuthenticatedKeyView toAuthKey(AuthenticatedApiKey result) {
    return new AccessSmokeResponses.AuthenticatedKeyView(
        Long.toString(result.apiKeyId()),
        Long.toString(result.tenantId()),
        Long.toString(result.knowledgeBaseId()),
        Long.toString(result.expiresAt().toEpochMilli()));
  }
}
