package ai.cerbur.crag.access.grpc.mapper;

import ai.cerbur.crag.access.core.session.AuthenticationResult;
import ai.cerbur.crag.contracts.access.v1.AuthenticationResponse;
import ai.cerbur.crag.contracts.access.v1.JwtVerificationKey;
import ai.cerbur.crag.contracts.access.v1.JwtVerificationKeySet;

/** Identity 核心 result 与 proto 互转。 */
public final class IdentityMapper {

  private IdentityMapper() {}

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
