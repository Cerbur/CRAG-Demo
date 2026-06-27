package ai.cerbur.crag.access.core.session;

import java.time.Instant;

/**
 * Access JWT 签发契约。实现使用 RS256 私钥签发身份型 JWT，只承载 {@code sub/sid/jti/iss/aud/iat/nbf/exp}，不含 Tenant 或角色。
 */
public interface JwtIssuer {

  /**
   * 签发身份型 Access JWT。
   *
   * @param userId 永久用户 ID（sub）
   * @param sessionFamilyId Session Family ID（sid）
   * @param issuedAt 签发时刻（控制 iat/nbf/exp）
   */
  IssuedJwt issue(long userId, long sessionFamilyId, Instant issuedAt);

  /** 当前可用于本地验签的公钥集，不返回私钥。 */
  JwtVerificationKeySet verificationKeys();
}
