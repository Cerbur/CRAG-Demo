package ai.cerbur.crag.console.security.jwt;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.time.Clock;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Access JWT 公钥缓存与 RS256 验签实现（plan_21/21.6）。
 *
 * <p>启动时从 Access {@code GetJwtVerificationKeys} 拉取公钥集（PEM）并按 {@code kid} 索引。验签流程：
 *
 * <ol>
 *   <li>解析 header，校验 {@code alg=RS256}；缺失或非 RS256 抛 {@link InvalidJwtException}。
 *   <li>按 {@code kid} 查找公钥；未知时抛 {@link UnknownJwtKidException}（由 refresher 触发一次刷新后重试）。
 *   <li>校验签名、payload 的 {@code iss/aud/exp/nbf}，解析 {@code sub/sid}。
 * </ol>
 *
 * <p>普通已登录请求仅依赖本地缓存；Access 不可达时，已缓存公钥仍可继续验证 15 分钟 Access JWT，不在线调用 Access。刷新由 {@link
 * AccessJwtKeyRefresher} 在捕获 {@link UnknownJwtKidException} 后触发一次；刷新后仍未命中视为稳定失败。
 */
public class JwtVerificationKeyCache implements AccessJwtVerifier {

  private static final Logger log = LoggerFactory.getLogger(JwtVerificationKeyCache.class);
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String ALGORITHM = "RS256";

  private final String expectedIssuer;
  private final String expectedAudience;
  private final Clock clock;
  private final Supplier<JwtKeySetSnapshot> refresher;

  private volatile Map<String, PublicKeyAndAlg> keysByKid = new HashMap<>();
  private final Object refreshLock = new Object();
  private volatile long lastRefreshEpochMillis = 0L;
  private static final long REFRESH_COOLDOWN_MILLIS = 30_000L;

  /** 构造初始缓存（无 refresher，用于不会遇到未知 kid 的场景或测试）. */
  public JwtVerificationKeyCache(
      ai.cerbur.crag.contracts.access.v1.JwtVerificationKeySet initial,
      String expectedIssuer,
      String expectedAudience) {
    this(initial, expectedIssuer, expectedAudience, () -> null, Clock.systemUTC());
  }

  /** 构造初始缓存与刷新函数. */
  public JwtVerificationKeyCache(
      ai.cerbur.crag.contracts.access.v1.JwtVerificationKeySet initial,
      String expectedIssuer,
      String expectedAudience,
      Supplier<JwtKeySetSnapshot> refresher) {
    this(initial, expectedIssuer, expectedAudience, refresher, Clock.systemUTC());
  }

  JwtVerificationKeyCache(
      ai.cerbur.crag.contracts.access.v1.JwtVerificationKeySet initial,
      String expectedIssuer,
      String expectedAudience,
      Supplier<JwtKeySetSnapshot> refresher,
      Clock clock) {
    this.expectedIssuer = expectedIssuer;
    this.expectedAudience = expectedAudience;
    this.refresher = refresher;
    this.clock = clock;
    this.keysByKid = indexInitial(initial);
  }

  /** 由 refresher 触发的单次刷新（线程安全，带冷却，避免反复在线调用）. */
  public boolean refreshOnce() {
    synchronized (refreshLock) {
      long now = clock.millis();
      if (now - lastRefreshEpochMillis < REFRESH_COOLDOWN_MILLIS && lastRefreshEpochMillis > 0L) {
        return false;
      }
      JwtKeySetSnapshot snapshot;
      try {
        snapshot = refresher.get();
      } catch (RuntimeException e) {
        log.warn("Access JWT 公钥刷新失败 — code=DOWNSTREAM_UNAVAILABLE");
        return false;
      }
      if (snapshot == null) {
        return false;
      }
      this.keysByKid = indexSnapshot(snapshot);
      lastRefreshEpochMillis = clock.millis();
      log.info("Access JWT 公钥缓存刷新成功 — keys={}", keysByKid.size());
      return true;
    }
  }

  @Override
  public ConsolePrincipal verify(String rawToken) {
    if (rawToken == null || rawToken.isBlank()) {
      throw new InvalidJwtException("empty token");
    }
    String[] parts = rawToken.split("\\.", 3);
    if (parts.length != 3) {
      throw new InvalidJwtException("malformed token");
    }
    JsonNode header = parseJson(b64Decode(parts[0]));
    String alg = header.path("alg").asText("");
    if (!ALGORITHM.equals(alg)) {
      throw new InvalidJwtException("unsupported alg: " + alg);
    }
    String kid = header.path("kid").asText("");
    PublicKeyAndAlg entry = lookup(kid);
    String signingInput = parts[0] + "." + parts[1];
    verifySignature(signingInput, parts[2], entry.key);
    JsonNode payload = parseJson(b64Decode(parts[1]));
    validateClaims(payload);
    long userId = parseClaimLong(payload, "sub");
    long sessionFamilyId = parseClaimLong(payload, "sid");
    return new ConsolePrincipal(userId, sessionFamilyId);
  }

  private PublicKeyAndAlg lookup(String kid) {
    PublicKeyAndAlg entry = keysByKid.get(kid);
    if (entry != null) {
      return entry;
    }
    // unknown kid → 单次刷新（带冷却）后重试
    refreshOnce();
    entry = keysByKid.get(kid);
    if (entry == null) {
      throw new UnknownJwtKidException(kid);
    }
    return entry;
  }

  private void validateClaims(JsonNode payload) {
    long now = clock.instant().getEpochSecond();
    long nbf = payload.path("nbf").asLong(0L);
    if (nbf > now) {
      throw new InvalidJwtException("token not yet valid");
    }
    long exp = payload.path("exp").asLong(0L);
    if (exp <= 0L || exp <= now) {
      throw new InvalidJwtException("token expired");
    }
    String iss = payload.path("iss").asText("");
    if (!expectedIssuer.equals(iss)) {
      throw new InvalidJwtException("iss mismatch");
    }
    JsonNode audNode = payload.path("aud");
    String aud = audNode.isTextual() ? audNode.asText() : "";
    if (!expectedAudience.equals(aud)) {
      throw new InvalidJwtException("aud mismatch");
    }
  }

  private static void verifySignature(String signingInput, String signatureB64, PublicKey key) {
    try {
      Signature verifier = Signature.getInstance("SHA256withRSA");
      verifier.initVerify(key);
      verifier.update(signingInput.getBytes(StandardCharsets.UTF_8));
      byte[] sig = b64Decode(signatureB64);
      if (!verifier.verify(sig)) {
        throw new InvalidJwtException("signature mismatch");
      }
    } catch (InvalidJwtException e) {
      throw e;
    } catch (Exception e) {
      throw new InvalidJwtException("verify error: " + e.getMessage());
    }
  }

  private static long parseClaimLong(JsonNode payload, String field) {
    String text = payload.path(field).asText("");
    try {
      return Long.parseLong(text.trim());
    } catch (NumberFormatException e) {
      throw new InvalidJwtException("invalid " + field + " claim");
    }
  }

  private static JsonNode parseJson(byte[] bytes) {
    try {
      return JSON.readTree(new String(bytes, StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new InvalidJwtException("json parse error");
    }
  }

  private static byte[] b64Decode(String b64url) {
    return Base64.getUrlDecoder().decode(b64url);
  }

  private static Map<String, PublicKeyAndAlg> indexInitial(
      ai.cerbur.crag.contracts.access.v1.JwtVerificationKeySet set) {
    Map<String, PublicKeyAndAlg> map = new HashMap<>();
    for (ai.cerbur.crag.contracts.access.v1.JwtVerificationKey k : set.getKeysList()) {
      map.put(k.getKid(), new PublicKeyAndAlg(parseRsaPem(k.getPublicKeyPem())));
    }
    return map;
  }

  private static Map<String, PublicKeyAndAlg> indexSnapshot(JwtKeySetSnapshot snapshot) {
    Map<String, PublicKeyAndAlg> map = new HashMap<>();
    for (JwtKeySetSnapshot.Entry e : snapshot.entries()) {
      map.put(e.kid(), new PublicKeyAndAlg(parseRsaPem(e.publicKeyPem())));
    }
    return map;
  }

  private static PublicKey parseRsaPem(String pem) {
    try {
      String content = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
      byte[] der = Base64.getDecoder().decode(content);
      java.security.spec.X509EncodedKeySpec spec = new java.security.spec.X509EncodedKeySpec(der);
      return KeyFactory.getInstance("RSA").generatePublic(spec);
    } catch (Exception e) {
      throw new IllegalStateException("invalid RSA public key PEM", e);
    }
  }

  private record PublicKeyAndAlg(PublicKey key) {}

  /** 便于测试的旧 RSAPublicKeySpec 路径占位（未使用，保留 BigInteger 解码供未来 JWK 扩展）. */
  @SuppressWarnings("unused")
  private static PublicKey fromModulus(BigInteger mod, BigInteger exp) throws Exception {
    return KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(mod, exp));
  }

  /** 由 refresher 返回的公钥快照（剥离 gRPC 类型，便于单元测试注入）. */
  public record JwtKeySetSnapshot(List<Entry> entries) {
    /** 单条公钥. */
    public record Entry(String kid, String publicKeyPem) {}
  }
}
