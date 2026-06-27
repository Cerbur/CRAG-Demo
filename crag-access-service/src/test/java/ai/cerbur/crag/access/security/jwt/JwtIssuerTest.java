package ai.cerbur.crag.access.security.jwt;

import static org.junit.jupiter.api.Assertions.*;

import ai.cerbur.crag.access.core.session.IssuedJwt;
import ai.cerbur.crag.access.core.session.JwtVerificationKey;
import ai.cerbur.crag.access.core.session.JwtVerificationKeySet;
import ai.cerbur.crag.access.security.AccessSecurityProperties;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** JwtIssuer 纯单元测试：固定 RSA 密钥与签发时刻下断言 JWT 结构、claims 与验签公钥集。 */
class JwtIssuerTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private JwtIssuerImpl issuer;

  @BeforeEach
  void setUp() throws Exception {
    issuer = newIssuer(generateProperties());
  }

  @Test
  @DisplayName("JWT 为三段式且 payload claims 精确，不含 tenant 或 role")
  void issueProducesCompactJwtWithIdentityClaims() throws Exception {
    Instant issuedAt = Instant.parse("2026-06-28T00:00:00Z");
    IssuedJwt issued = issuer.issue(123L, 456L, issuedAt);

    String[] parts = issued.token().split("\\.");
    assertEquals(3, parts.length);

    Map<String, Object> claims = JSON.readValue(decode(parts[1]), Map.class);
    assertEquals("123", claims.get("sub"));
    assertEquals("456", claims.get("sid"));
    assertEquals("test-issuer", claims.get("iss"));
    assertEquals("test-audience", claims.get("aud"));
    assertNotNull(claims.get("jti"));
    assertEquals(issuedAt.getEpochSecond(), ((Number) claims.get("iat")).longValue() / 1000);
    assertEquals(claims.get("iat"), claims.get("nbf"));
    assertEquals(
        ((Number) claims.get("iat")).longValue() + 900_000L,
        ((Number) claims.get("exp")).longValue());
    assertFalse(claims.containsKey("tenantId"), "JWT must not carry tenantId");
    assertFalse(claims.containsKey("role"), "JWT must not carry role");
    assertEquals(issuedAt.plusSeconds(900), issued.expiresAt());

    Map<String, Object> header = JSON.readValue(decode(parts[0]), Map.class);
    assertEquals("RS256", header.get("alg"));
    assertEquals("test-kid", header.get("kid"));
  }

  @Test
  @DisplayName("verificationKeys 返回当前公钥集且不含私钥")
  void verificationKeysExposePublicKeyOnly() {
    JwtVerificationKeySet keys = issuer.verificationKeys();
    assertEquals(1, keys.keys().size());
    JwtVerificationKey key = keys.keys().get(0);
    assertEquals("test-kid", key.kid());
    assertEquals("RS256", key.algorithm());
    assertTrue(key.publicKeyPem().contains("BEGIN PUBLIC KEY"));
    assertFalse(key.publicKeyPem().contains("PRIVATE"));
  }

  @Test
  @DisplayName("缺失密钥时签发抛 IllegalStateException")
  void issueFailsWithoutKeys() {
    JwtIssuerImpl blank = new JwtIssuerImpl(new AccessSecurityProperties());
    assertThrows(IllegalStateException.class, () -> blank.issue(1L, 1L, Instant.now()));
  }

  private static JwtIssuerImpl newIssuer(AccessSecurityProperties properties) {
    return new JwtIssuerImpl(properties);
  }

  private static AccessSecurityProperties generateProperties() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    KeyPair keyPair = generator.generateKeyPair();
    AccessSecurityProperties properties = new AccessSecurityProperties();
    properties.getJwt().setPrivateKeyPem(toPem("PRIVATE KEY", keyPair.getPrivate().getEncoded()));
    properties.getJwt().setPublicKeyPem(toPem("PUBLIC KEY", keyPair.getPublic().getEncoded()));
    properties.getJwt().setKid("test-kid");
    properties.getJwt().setIssuer("test-issuer");
    properties.getJwt().setAudience("test-audience");
    properties.getJwt().setAccessTtlSeconds(900);
    return properties;
  }

  private static String decode(String base64Url) {
    return new String(Base64.getUrlDecoder().decode(base64Url));
  }

  private static String toPem(String type, byte[] der) {
    String base64 = Base64.getEncoder().encodeToString(der);
    StringBuilder sb = new StringBuilder("-----BEGIN ").append(type).append("-----\n");
    for (int i = 0; i < base64.length(); i += 64) {
      sb.append(base64, i, Math.min(i + 64, base64.length())).append('\n');
    }
    return sb.append("-----END ").append(type).append("-----\n").toString();
  }
}
