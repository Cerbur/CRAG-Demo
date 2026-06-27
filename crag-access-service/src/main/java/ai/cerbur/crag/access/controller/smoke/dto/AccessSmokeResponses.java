package ai.cerbur.crag.access.controller.smoke.dto;

import java.util.List;

/** Access smoke 响应 DTO 集合（仅 smoke Profile）；不含密码、HMAC 或 Pepper。 */
public final class AccessSmokeResponses {

  private AccessSmokeResponses() {}

  public record AuthResponse(
      String userId,
      String nickname,
      String tenantId,
      String accessToken,
      String refreshToken,
      String sessionFamilyId) {}

  public record JwtKey(String kid, String algorithm, String publicKeyPem) {}

  public record JwtKeysResponse(List<JwtKey> keys) {}

  public record MembershipView(
      String membershipId,
      String tenantId,
      String userId,
      String role,
      String status,
      long version) {}

  public record ScopeView(String knowledgeBaseId, String tenantId, String status, long version) {}

  public record CreatedKeyView(
      String apiKeyId,
      String tenantId,
      String knowledgeBaseId,
      String name,
      String completeKey,
      String expiresAtEpochMillis) {}

  public record KeyView(
      String apiKeyId,
      String tenantId,
      String knowledgeBaseId,
      String name,
      String status,
      String keyPrefix,
      String expiresAtEpochMillis,
      long version) {}

  public record AuthenticatedKeyView(
      String apiKeyId, String tenantId, String knowledgeBaseId, String expiresAtEpochMillis) {}
}
