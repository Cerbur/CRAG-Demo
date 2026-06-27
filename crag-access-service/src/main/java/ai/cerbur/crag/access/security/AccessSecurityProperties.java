package ai.cerbur.crag.access.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Access 安全配置，绑定 {@code crag.access.*}。
 *
 * <p>Argon2 参数默认为基线（64 MiB / 3 次迭代 / 并行度 1 / 16 字节 Salt / 32 字节输出），可通过配置提高但不得低于基线。 JWT 私钥/公钥与两份
 * Pepper 在正式 Profile 无默认值，必须由环境提供；缺失时就绪检查报 DOWN。
 */
@ConfigurationProperties(prefix = "crag.access")
public class AccessSecurityProperties {

  private Jwt jwt = new Jwt();
  private Argon2 argon2 = new Argon2();
  private Pepper pepper = new Pepper();

  public Jwt getJwt() {
    return jwt;
  }

  public void setJwt(Jwt jwt) {
    this.jwt = jwt;
  }

  public Argon2 getArgon2() {
    return argon2;
  }

  public void setArgon2(Argon2 argon2) {
    this.argon2 = argon2;
  }

  public Pepper getPepper() {
    return pepper;
  }

  public void setPepper(Pepper pepper) {
    this.pepper = pepper;
  }

  /** RS256 JWT 签名配置。 */
  public static class Jwt {
    private String privateKeyPem = "";
    private String publicKeyPem = "";
    private String kid = "";
    private String issuer = "";
    private String audience = "";

    /** Access JWT 有效期秒数，默认 15 分钟。 */
    private long accessTtlSeconds = 900;

    public String getPrivateKeyPem() {
      return privateKeyPem;
    }

    public void setPrivateKeyPem(String privateKeyPem) {
      this.privateKeyPem = privateKeyPem;
    }

    public String getPublicKeyPem() {
      return publicKeyPem;
    }

    public void setPublicKeyPem(String publicKeyPem) {
      this.publicKeyPem = publicKeyPem;
    }

    public String getKid() {
      return kid;
    }

    public void setKid(String kid) {
      this.kid = kid;
    }

    public String getIssuer() {
      return issuer;
    }

    public void setIssuer(String issuer) {
      this.issuer = issuer;
    }

    public String getAudience() {
      return audience;
    }

    public void setAudience(String audience) {
      this.audience = audience;
    }

    public long getAccessTtlSeconds() {
      return accessTtlSeconds;
    }

    public void setAccessTtlSeconds(long accessTtlSeconds) {
      this.accessTtlSeconds = accessTtlSeconds;
    }
  }

  /** Argon2id 参数基线。 */
  public static class Argon2 {
    private int memoryKiB = 65536;
    private int iterations = 3;
    private int parallelism = 1;
    private int saltLength = 16;
    private int hashLength = 32;

    public int getMemoryKiB() {
      return memoryKiB;
    }

    public void setMemoryKiB(int memoryKiB) {
      this.memoryKiB = memoryKiB;
    }

    public int getIterations() {
      return iterations;
    }

    public void setIterations(int iterations) {
      this.iterations = iterations;
    }

    public int getParallelism() {
      return parallelism;
    }

    public void setParallelism(int parallelism) {
      this.parallelism = parallelism;
    }

    public int getSaltLength() {
      return saltLength;
    }

    public void setSaltLength(int saltLength) {
      this.saltLength = saltLength;
    }

    public int getHashLength() {
      return hashLength;
    }

    public void setHashLength(int hashLength) {
      this.hashLength = hashLength;
    }
  }

  /** Refresh Token 与 API Key 专用 Pepper，正式 Profile 无默认值。 */
  public static class Pepper {
    private String refreshToken = "";
    private String apiKey = "";

    public String getRefreshToken() {
      return refreshToken;
    }

    public void setRefreshToken(String refreshToken) {
      this.refreshToken = refreshToken;
    }

    public String getApiKey() {
      return apiKey;
    }

    public void setApiKey(String apiKey) {
      this.apiKey = apiKey;
    }
  }
}
