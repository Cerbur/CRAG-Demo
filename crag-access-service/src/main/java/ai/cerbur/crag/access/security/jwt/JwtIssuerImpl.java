package ai.cerbur.crag.access.security.jwt;

import ai.cerbur.crag.access.core.session.IssuedJwt;
import ai.cerbur.crag.access.core.session.JwtIssuer;
import ai.cerbur.crag.access.core.session.JwtVerificationKey;
import ai.cerbur.crag.access.core.session.JwtVerificationKeySet;
import ai.cerbur.crag.access.security.AccessSecurityProperties;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * RS256 Access JWT 签发实现（纯 JDK）。
 *
 * <p>只承载身份/会话声明：{@code sub/sid/jti/iss/aud/iat/nbf/exp}，不含 Tenant 或角色。密钥材料在首次签发时懒解析并缓存；缺失或无效时抛
 * {@link IllegalStateException}。
 */
@Component
public class JwtIssuerImpl implements JwtIssuer {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String ALGORITHM = "RS256";

  private final AccessSecurityProperties properties;

  private volatile AccessJwtKeyMaterial keyMaterial;

  // 构造器注入：安全适配器需要可脱离 Spring 手工构造以支持 JWT 签名单元测试。
  @Autowired
  public JwtIssuerImpl(AccessSecurityProperties properties) {
    this.properties = properties;
  }

  @Override
  public IssuedJwt issue(long userId, long sessionFamilyId, Instant issuedAt) {
    AccessJwtKeyMaterial km = keyMaterial();
    long ttlSeconds = Math.max(1, properties.getJwt().getAccessTtlSeconds());
    // iat/nbf/exp 使用 epoch seconds（RFC 7519 NumericDate），与 Console/Open 验签器一致。
    long iatSeconds = issuedAt.getEpochSecond();
    long expSeconds = iatSeconds + ttlSeconds;
    String header = toJson(Map.of("alg", ALGORITHM, "typ", "JWT", "kid", km.getKid()));
    Map<String, Object> claims = new LinkedHashMap<>();
    claims.put("sub", Long.toString(userId));
    claims.put("sid", Long.toString(sessionFamilyId));
    claims.put("jti", UUID.randomUUID().toString());
    claims.put("iss", km.getIssuer());
    claims.put("aud", km.getAudience());
    claims.put("iat", iatSeconds);
    claims.put("nbf", iatSeconds);
    claims.put("exp", expSeconds);
    String payload = toJson(claims);
    String signingInput = base64Url(header) + "." + base64Url(payload);
    byte[] signature = sign(signingInput, km.getPrivateKey());
    return new IssuedJwt(
        signingInput + "." + base64Url(signature), Instant.ofEpochSecond(expSeconds));
  }

  @Override
  public JwtVerificationKeySet verificationKeys() {
    AccessJwtKeyMaterial km = keyMaterial();
    return new JwtVerificationKeySet(
        List.of(new JwtVerificationKey(km.getKid(), ALGORITHM, km.getPublicKeyPem())));
  }

  private AccessJwtKeyMaterial keyMaterial() {
    AccessJwtKeyMaterial cached = keyMaterial;
    if (cached == null) {
      synchronized (this) {
        cached = keyMaterial;
        if (cached == null) {
          cached = AccessJwtKeyMaterial.parse(properties.getJwt());
          keyMaterial = cached;
        }
      }
    }
    return cached;
  }

  private static byte[] sign(
      String signingInput, java.security.interfaces.RSAPrivateKey privateKey) {
    try {
      Signature signature = Signature.getInstance("SHA256withRSA");
      signature.initSign(privateKey);
      signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
      return signature.sign();
    } catch (Exception e) {
      throw new IllegalStateException("JWT signing failed", e);
    }
  }

  private static String toJson(Map<String, Object> map) {
    try {
      return JSON.writeValueAsString(map);
    } catch (Exception e) {
      throw new IllegalStateException("JWT JSON serialization failed", e);
    }
  }

  private static String base64Url(String value) {
    return base64Url(value.getBytes(StandardCharsets.UTF_8));
  }

  private static String base64Url(byte[] bytes) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
