package ai.cerbur.crag.access.grpc.mapper;

import ai.cerbur.crag.access.core.identity.UserProfileResult;
import ai.cerbur.crag.access.core.membership.UserTenantResult;
import ai.cerbur.crag.access.core.session.AuthenticationResult;
import ai.cerbur.crag.contracts.access.v1.AuthenticationResponse;
import ai.cerbur.crag.contracts.access.v1.JwtVerificationKey;
import ai.cerbur.crag.contracts.access.v1.JwtVerificationKeySet;
import ai.cerbur.crag.contracts.access.v1.UserProfile;
import ai.cerbur.crag.contracts.access.v1.UserTenant;
import java.util.List;

/** Identity 核心 result 与 proto 互转。 */
public final class IdentityMapper {

  private IdentityMapper() {}

  /** router4 用户安全投影映射。 */
  public static UserProfile toProto(UserProfileResult result) {
    return UserProfile.newBuilder()
        .setUserId(Long.toString(result.userId()))
        .setNickname(result.nickname())
        .build();
  }

  /** router4 用户 Tenant 投影映射。 */
  public static UserTenant toProto(UserTenantResult result) {
    return UserTenant.newBuilder()
        .setTenantId(Long.toString(result.tenantId()))
        .setName(result.name())
        .setRole(toProtoRole(result.role()))
        .build();
  }

  public static ai.cerbur.crag.contracts.access.v1.ListUserTenantsResponse toProtoUserTenants(
      List<UserTenantResult> items, String nextPageToken) {
    var builder = ai.cerbur.crag.contracts.access.v1.ListUserTenantsResponse.newBuilder();
    items.forEach(i -> builder.addTenants(toProto(i)));
    if (nextPageToken != null) {
      builder.setNextPageToken(nextPageToken);
    }
    return builder.build();
  }

  private static ai.cerbur.crag.contracts.access.v1.MembershipRole toProtoRole(
      ai.cerbur.crag.access.core.membership.MembershipRole role) {
    return role == ai.cerbur.crag.access.core.membership.MembershipRole.OWNER
        ? ai.cerbur.crag.contracts.access.v1.MembershipRole.MEMBERSHIP_ROLE_OWNER
        : ai.cerbur.crag.contracts.access.v1.MembershipRole.MEMBERSHIP_ROLE_MEMBER;
  }

  public static AuthenticationResponse toProto(AuthenticationResult result) {
    return AuthenticationResponse.newBuilder()
        .setUserId(Long.toString(result.userId()))
        .setNickname(result.nickname())
        .setAccessToken(result.tokens().accessToken())
        .setAccessExpiresAtEpochMillis(
            Long.toString(result.tokens().accessExpiresAt().toEpochMilli()))
        .setRefreshToken(result.tokens().refreshToken())
        .setRefreshExpiresAtEpochMillis(
            Long.toString(result.tokens().refreshExpiresAt().toEpochMilli()))
        .setSessionFamilyId(Long.toString(result.tokens().sessionFamilyId()))
        .build();
  }

  public static JwtVerificationKeySet toProto(
      ai.cerbur.crag.access.core.session.JwtVerificationKeySet keys) {
    JwtVerificationKeySet.Builder builder = JwtVerificationKeySet.newBuilder();
    for (ai.cerbur.crag.access.core.session.JwtVerificationKey key : keys.keys()) {
      builder.addKeys(
          JwtVerificationKey.newBuilder()
              .setKid(key.kid())
              .setAlgorithm(key.algorithm())
              .setPublicKeyPem(key.publicKeyPem())
              .build());
    }
    return builder.build();
  }
}
