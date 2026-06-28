package ai.cerbur.crag.console.security.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.cerbur.crag.contracts.access.v1.JwtVerificationKey;
import ai.cerbur.crag.contracts.access.v1.JwtVerificationKeySet;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * AccessJwtVerifier 纯单元测试（plan_21/21.6）。
 *
 * <p>断言 RS256 验签严格校验 kid/alg/iss/aud/exp/nbf；算法不符、kid 未知、声明不匹配或签名错误均拒绝。 不依赖 Spring Context。
 */
@DisplayName("AccessJwtVerifier RS256 验签")
class AccessJwtVerifierTest {

  private KeyPair pair;
  private JwtVerificationKeyCache cache;
  private AccessJwtVerifier verifier;

  @BeforeEach
  void setUp() throws Exception {
    KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
    gen.initialize(2048);
    pair = gen.generateKeyPair();
    cache =
        new JwtVerificationKeyCache(
            keySet("kid-1", "RS256", (RSAPublicKey) pair.getPublic()),
            "crag-access",
            "console-api");
    verifier = cache;
  }

  @Test
  @DisplayName("有效 JWT → 返回 ConsolePrincipal(userId, sessionFamilyId)")
  void verifiesValidJwt() {
    String jwt =
        signJwt("kid-1", "RS256", claims("123", "456", "crag-access", "console-api", 0, 900));
    ConsolePrincipal principal = verifier.verify(jwt);
    assertThat(principal.userId()).isEqualTo(123L);
    assertThat(principal.sessionFamilyId()).isEqualTo(456L);
  }

  @Test
  @DisplayName("未知 kid → 抛 UnknownJwtKidException，调用方可触发一次刷新")
  void rejectsUnknownKid() {
    String jwt = signJwt("kid-2", "RS256", claims("1", "2", "crag-access", "console-api", 0, 900));
    assertThatThrownBy(() -> verifier.verify(jwt)).isInstanceOf(UnknownJwtKidException.class);
  }

  @Test
  @DisplayName("算法不符（HS256 伪装）→ 拒绝")
  void rejectsWrongAlgorithm() {
    String jwt = signJwt("kid-1", "HS256", claims("1", "2", "crag-access", "console-api", 0, 900));
    assertThatThrownBy(() -> verifier.verify(jwt)).isInstanceOf(InvalidJwtException.class);
  }

  @Test
  @DisplayName("签名错误 → 拒绝")
  void rejectsBadSignature() {
    String jwt =
        signJwt("kid-1", "RS256", claims("1", "2", "crag-access", "console-api", 0, 900))
                .substring(0, 40)
            + ".AAAA";
    assertThatThrownBy(() -> verifier.verify(jwt)).isInstanceOf(InvalidJwtException.class);
  }

  @Test
  @DisplayName("过期（exp 已过）→ 拒绝")
  void rejectsExpired() {
    String jwt =
        signJwt("kid-1", "RS256", claims("1", "2", "crag-access", "console-api", -1800, -900));
    assertThatThrownBy(() -> verifier.verify(jwt)).isInstanceOf(InvalidJwtException.class);
  }

  @Test
  @DisplayName("nbf 在未来 → 拒绝")
  void rejectsNotYetValid() {
    String jwt =
        signJwt("kid-1", "RS256", claims("1", "2", "crag-access", "console-api", 0, 900), 600);
    assertThatThrownBy(() -> verifier.verify(jwt)).isInstanceOf(InvalidJwtException.class);
  }

  @Test
  @DisplayName("iss 不匹配 → 拒绝")
  void rejectsWrongIssuer() {
    String jwt = signJwt("kid-1", "RS256", claims("1", "2", "evil", "console-api", 0, 900));
    assertThatThrownBy(() -> verifier.verify(jwt)).isInstanceOf(InvalidJwtException.class);
  }

  @Test
  @DisplayName("aud 不匹配 → 拒绝")
  void rejectsWrongAudience() {
    String jwt = signJwt("kid-1", "RS256", claims("1", "2", "crag-access", "evil", 0, 900));
    assertThatThrownBy(() -> verifier.verify(jwt)).isInstanceOf(InvalidJwtException.class);
  }

  // ---- helpers ----

  private Map<String, Object> claims(
      String sub, String sid, String iss, String aud, long nbfOffset, long expOffset) {
    long now = System.currentTimeMillis() / 1000;
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("sub", sub);
    m.put("sid", sid);
    m.put("iss", iss);
    m.put("aud", aud);
    m.put("iat", now);
    m.put("nbf", now + nbfOffset);
    m.put("exp", now + expOffset);
    return m;
  }

  private String signJwt(String kid, String alg, Map<String, Object> claims) {
    return signJwt(kid, alg, claims, 0L);
  }

  private String signJwt(
      String kid, String alg, Map<String, Object> claims, long nbfFutureSeconds) {
    if (nbfFutureSeconds != 0L) {
      long now = System.currentTimeMillis() / 1000;
      claims.put("nbf", now + nbfFutureSeconds);
    }
    String header = json(Map.of("kid", kid, "alg", alg, "typ", "JWT"));
    String payload = json(claims);
    String signingInput = b64(header) + "." + b64(payload);
    try {
      Signature sig = Signature.getInstance("SHA256withRSA");
      sig.initSign((RSAPrivateKey) pair.getPrivate());
      sig.update(signingInput.getBytes(StandardCharsets.UTF_8));
      return signingInput + "." + b64Bytes(sig.sign());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static String json(Map<String, Object> m) {
    StringBuilder sb = new StringBuilder("{");
    boolean first = true;
    for (var e : m.entrySet()) {
      if (!first) sb.append(",");
      first = false;
      sb.append("\"").append(e.getKey()).append("\":");
      Object v = e.getValue();
      if (v instanceof Number) sb.append(v);
      else sb.append("\"").append(v).append("\"");
    }
    return sb.append("}").toString();
  }

  private static String b64(String s) {
    return b64Bytes(s.getBytes(StandardCharsets.UTF_8));
  }

  private static String b64Bytes(byte[] b) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }

  private static JwtVerificationKeySet keySet(String kid, String alg, RSAPublicKey key) {
    return JwtVerificationKeySet.newBuilder()
        .addKeys(
            JwtVerificationKey.newBuilder()
                .setKid(kid)
                .setAlgorithm(alg)
                .setPublicKeyPem(toPem(key))
                .build())
        .build();
  }

  private static String toPem(RSAPublicKey key) {
    byte[] der = key.getEncoded();
    String b64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der);
    return "-----BEGIN PUBLIC KEY-----\n" + b64 + "\n-----END PUBLIC KEY-----";
  }
}
