package ai.cerbur.crag.access.controller.smoke.dto;

import jakarta.validation.constraints.NotBlank;

/** Access smoke 请求 DTO 集合（仅 smoke Profile）。 */
public final class AccessSmokeRequests {

  private AccessSmokeRequests() {}

  public record RegisterRequest(
      @NotBlank String nickname, @NotBlank String username, @NotBlank String password) {}

  public record LoginRequest(@NotBlank String username, @NotBlank String password) {}

  public record RefreshRequest(@NotBlank String refreshToken) {}

  public record LogoutRequest(@NotBlank String userId, @NotBlank String sessionFamilyId) {}

  public record ActorTenant(@NotBlank String actorUserId, @NotBlank String tenantId) {}

  public record AddMemberRequest(
      @NotBlank String actorUserId, @NotBlank String tenantId, @NotBlank String username) {}

  public record ChangeRoleRequest(
      @NotBlank String actorUserId, @NotBlank String tenantId, @NotBlank String role) {}

  public record RegisterScopeRequest(
      @NotBlank String actorUserId, @NotBlank String tenantId, @NotBlank String knowledgeBaseId) {}

  public record CreateApiKeyRequest(
      @NotBlank String actorUserId,
      @NotBlank String tenantId,
      @NotBlank String knowledgeBaseId,
      @NotBlank String name,
      Long ttlSeconds) {}

  public record RotateApiKeyRequest(
      @NotBlank String actorUserId, @NotBlank String tenantId, Long ttlSeconds) {}

  public record AuthenticateApiKeyRequest(@NotBlank String apiKey) {}
}
