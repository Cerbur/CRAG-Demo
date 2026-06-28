package ai.cerbur.crag.console.security.jwt;

import ai.cerbur.crag.console.auth.service.AccessIdentityClient;
import ai.cerbur.crag.contracts.access.v1.JwtVerificationKeySet;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Access JWT 公钥加载与刷新协调器（plan_21/21.6）。
 *
 * <p>启动时通过 AccessIdentityClient 拉取公钥集构造 {@link JwtVerificationKeyCache}；未知 {@code kid} 由 cache
 * 内部触发一次带冷却的在线刷新。 普通已登录请求不触发在线调用。Readiness 要求：公钥从未成功加载时，认证类请求必须失败（见 Bearer filter）。
 */
@Component
public class AccessJwtKeyRefresher {

  private static final Logger log = LoggerFactory.getLogger(AccessJwtKeyRefresher.class);

  private final AccessIdentityClient identityClient;
  private final JwtVerificationKeyCache cache;
  private volatile boolean initialLoadOk;

  @Autowired
  public AccessJwtKeyRefresher(
      AccessIdentityClient identityClient,
      @Value("${crag.console.jwt.issuer:crag-access}") String issuer,
      @Value("${crag.console.jwt.audience:console-api}") String audience) {
    this.identityClient = identityClient;
    JwtKeySet initial = loadOrEmpty();
    this.cache =
        new JwtVerificationKeyCache(
            initial.set(),
            issuer,
            audience,
            () -> {
              JwtVerificationKeySet fresh = identityClient.loadVerificationKeys();
              List<JwtVerificationKeyCache.JwtKeySetSnapshot.Entry> entries = new ArrayList<>();
              for (ai.cerbur.crag.contracts.access.v1.JwtVerificationKey k : fresh.getKeysList()) {
                entries.add(
                    new JwtVerificationKeyCache.JwtKeySetSnapshot.Entry(
                        k.getKid(), k.getPublicKeyPem()));
              }
              return new JwtVerificationKeyCache.JwtKeySetSnapshot(entries);
            });
    this.initialLoadOk = initial.ok;
  }

  /** 返回当前 verifier（也是缓存）。 */
  public AccessJwtVerifier verifier() {
    return cache;
  }

  /** 初始公钥是否成功加载。 */
  public boolean isInitialLoadOk() {
    return initialLoadOk;
  }

  private JwtKeySet loadOrEmpty() {
    try {
      JwtVerificationKeySet set = identityClient.loadVerificationKeys();
      return new JwtKeySet(set, true);
    } catch (RuntimeException e) {
      log.error("Access JWT 公钥初始加载失败 — 认证类请求将拒绝直至 Access 可达");
      return new JwtKeySet(JwtVerificationKeySet.getDefaultInstance(), false);
    }
  }

  private record JwtKeySet(JwtVerificationKeySet set, boolean ok) {}
}
