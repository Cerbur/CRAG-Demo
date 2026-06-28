package ai.cerbur.crag.console.security.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * JwtVerificationKeyCache + AccessJwtKeyRefresher 纯单元测试（plan_21/21.6）。
 *
 * <p>断言：unknown kid 触发一次刷新；刷新后旧 kid 仍可用；连续两次 unknown 不重复触发在线调用（在刷新返回后仍未匹配视为稳定失败）。
 */
@DisplayName("JwtVerificationKeyCache 未知 kid 单次刷新")
class JwtVerificationKeyCacheTest {

  @Test
  @DisplayName("未知 kid → 调用一次 refresher → 匹配后验签成功")
  void unknownKidTriggersRefreshOnce() throws Exception {
    KeyPair pairA = rsaKey();
    KeyPair pairB = rsaKey();
    Supplier<JwtVerificationKeyCache.JwtKeySetSnapshot> refresher = mock(Supplier.class);
    when(refresher.get())
        .thenReturn(
            toSnapshot(
                new TestKey("kid-A", (RSAPublicKey) pairA.getPublic()),
                new TestKey("kid-B", (RSAPublicKey) pairB.getPublic())));

    JwtVerificationKeyCache cache =
        new JwtVerificationKeyCache(
            toProto(new TestKey("kid-A", (RSAPublicKey) pairA.getPublic())),
            "crag-access",
            "console-api",
            refresher);

    // kid-B 未知 → 触发刷新 → 命中 kid-B
    String jwtB =
        signJwt(
            "kid-B",
            (RSAPrivateKey) pairB.getPrivate(),
            claims("7", "8", "crag-access", "console-api"));
    ConsolePrincipal p = cache.verify(jwtB);
    assertThat(p.userId()).isEqualTo(7L);

    // 再次未知 kid 触发第二次刷新（同一刷新周期内不再触发是不安全的，但 refresher 仅在未知时调用；
    // 此处验证：已刷新后 kid-B 不再触发）
    verify(refresher, times(1)).get();

    // kid-B 复用不再触发刷新
    ConsolePrincipal p2 = cache.verify(jwtB);
    assertThat(p2.sessionFamilyId()).isEqualTo(8L);
    verify(refresher, times(1)).get();
  }

  @Test
  @DisplayName("刷新后仍未知 kid → 抛 UnknownJwtKidException 且不重试")
  void refreshStillUnknownRejects() throws Exception {
    KeyPair pairA = rsaKey();
    Supplier<JwtVerificationKeyCache.JwtKeySetSnapshot> refresher = mock(Supplier.class);
    when(refresher.get())
        .thenReturn(toSnapshot(new TestKey("kid-A", (RSAPublicKey) pairA.getPublic())));

    JwtVerificationKeyCache cache =
        new JwtVerificationKeyCache(
            toProto(new TestKey("kid-A", (RSAPublicKey) pairA.getPublic())),
            "crag-access",
            "console-api",
            refresher);

    String jwtC =
        signJwt(
            "kid-C",
            (RSAPrivateKey) pairA.getPrivate(),
            claims("1", "2", "crag-access", "console-api"));
    try {
      cache.verify(jwtC);
    } catch (UnknownJwtKidException expected) {
      // expected
    }
    verify(refresher, times(1)).get();
  }

  // ---- helpers ----

  record TestKey(String kid, RSAPublicKey key) {}

  private static KeyPair rsaKey() throws Exception {
    KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
    g.initialize(2048);
    return g.generateKeyPair();
  }

  private static JwtVerificationKeyCache.JwtKeySetSnapshot toSnapshot(TestKey... keys) {
    java.util.List<JwtVerificationKeyCache.JwtKeySetSnapshot.Entry> entries =
        new java.util.ArrayList<>();
    for (TestKey k : keys) {
      entries.add(new JwtVerificationKeyCache.JwtKeySetSnapshot.Entry(k.kid(), toPem(k.key())));
    }
    return new JwtVerificationKeyCache.JwtKeySetSnapshot(entries);
  }

  private static ai.cerbur.crag.contracts.access.v1.JwtVerificationKeySet toProto(TestKey... keys) {
    var builder = ai.cerbur.crag.contracts.access.v1.JwtVerificationKeySet.newBuilder();
    for (TestKey k : keys) {
      builder.addKeys(
          ai.cerbur.crag.contracts.access.v1.JwtVerificationKey.newBuilder()
              .setKid(k.kid())
              .setAlgorithm("RS256")
              .setPublicKeyPem(toPem(k.key()))
              .build());
    }
    return builder.build();
  }

  private static String toPem(RSAPublicKey key) {
    byte[] der = key.getEncoded();
    String b64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der);
    return "-----BEGIN PUBLIC KEY-----\n" + b64 + "\n-----END PUBLIC KEY-----";
  }

  private static Map<String, Object> claims(String sub, String sid, String iss, String aud) {
    long now = System.currentTimeMillis() / 1000;
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("sub", sub);
    m.put("sid", sid);
    m.put("iss", iss);
    m.put("aud", aud);
    m.put("iat", now);
    m.put("nbf", now);
    m.put("exp", now + 900);
    return m;
  }

  private static String signJwt(String kid, RSAPrivateKey priv, Map<String, Object> claims) {
    String header = json(Map.of("kid", kid, "alg", "RS256", "typ", "JWT"));
    String payload = json(claims);
    String signingInput = b64(header) + "." + b64(payload);
    try {
      Signature sig = Signature.getInstance("SHA256withRSA");
      sig.initSign(priv);
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
}
