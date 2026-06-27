package ai.cerbur.crag.access.security.jwt;

import ai.cerbur.crag.access.security.AccessSecurityProperties;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Access JWT RSA 密钥材料，从受控配置的 PEM 解析。
 *
 * <p>私钥用于 RS256 签名，公钥通过 gRPC 暴露给 router4 本地验签。密钥长度不得低于 2048 位。解析失败（缺失或格式错误）抛 {@link
 * IllegalStateException}，由调用边界转译；正式 Profile 缺失密钥时由 accessSecrets 就绪检查报 DOWN，应用仍可启动。
 */
public final class AccessJwtKeyMaterial {

  private final RSAPrivateKey privateKey;
  private final RSAPublicKey publicKey;
  private final String publicKeyPem;
  private final String kid;
  private final String issuer;
  private final String audience;

  private AccessJwtKeyMaterial(
      RSAPrivateKey privateKey,
      RSAPublicKey publicKey,
      String publicKeyPem,
      String kid,
      String issuer,
      String audience) {
    this.privateKey = privateKey;
    this.publicKey = publicKey;
    this.publicKeyPem = publicKeyPem;
    this.kid = kid;
    this.issuer = issuer;
    this.audience = audience;
  }

  /** 从 JWT 配置解析密钥材料；校验 RSA 长度不低于 2048 位。 */
  public static AccessJwtKeyMaterial parse(AccessSecurityProperties.Jwt jwt) {
    if (isBlank(jwt.getPrivateKeyPem()) || isBlank(jwt.getPublicKeyPem())) {
      throw new IllegalStateException("Access JWT keys are not configured");
    }
    RSAPrivateKey privateKey = parsePrivateKey(jwt.getPrivateKeyPem());
    RSAPublicKey publicKey = parsePublicKey(jwt.getPublicKeyPem());
    if (privateKey.getModulus().bitLength() < 2048 || publicKey.getModulus().bitLength() < 2048) {
      throw new IllegalStateException("Access JWT RSA key must be at least 2048 bits");
    }
    return new AccessJwtKeyMaterial(
        privateKey,
        publicKey,
        jwt.getPublicKeyPem(),
        jwt.getKid(),
        jwt.getIssuer(),
        jwt.getAudience());
  }

  static RSAPrivateKey parsePrivateKey(String pem) {
    try {
      byte[] der = decodePem(pem, "PRIVATE KEY");
      return (RSAPrivateKey)
          KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    } catch (Exception e) {
      throw new IllegalStateException("invalid RSA private key PEM", e);
    }
  }

  static RSAPublicKey parsePublicKey(String pem) {
    try {
      byte[] der = decodePem(pem, "PUBLIC KEY");
      return (RSAPublicKey)
          KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
    } catch (Exception e) {
      throw new IllegalStateException("invalid RSA public key PEM", e);
    }
  }

  private static byte[] decodePem(String pem, String type) {
    String body =
        pem.replace("-----BEGIN " + type + "-----", "")
            .replace("-----END " + type + "-----", "")
            .replaceAll("\\s", "");
    return Base64.getDecoder().decode(body);
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  public RSAPrivateKey getPrivateKey() {
    return privateKey;
  }

  public RSAPublicKey getPublicKey() {
    return publicKey;
  }

  public String getPublicKeyPem() {
    return publicKeyPem;
  }

  public String getKid() {
    return kid;
  }

  public String getIssuer() {
    return issuer;
  }

  public String getAudience() {
    return audience;
  }
}
