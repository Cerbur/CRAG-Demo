package ai.cerbur.crag.open.auth.service;

import ai.cerbur.crag.open.auth.service.AccessApiKeyClient.DownstreamUnavailableException;
import ai.cerbur.crag.open.auth.service.AccessApiKeyClient.InvalidApiKeyException;
import ai.cerbur.crag.open.authcache.ApiKeyAuthCache;
import ai.cerbur.crag.open.authcache.CachedApiKey;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Open API Key 鉴权编排（plan_21/21.10）。
 *
 * <p>流程：本地缓存查询 → 缓存未命中时调用 Access {@code AuthenticateApiKey} → 写入缓存（版本水位校验）。
 *
 * <p>降级策略：
 *
 * <ul>
 *   <li>缓存命中（未过期、版本水位一致）→ 直接返回，不调用 Access。
 *   <li>缓存未命中 → 调用 Access 在线鉴权；鉴权失败抛 {@link InvalidApiKeyException}（40102）。
 *   <li>Access 不可用时抛 {@link DownstreamUnavailableException}（50301）；Redis 降级不在此层（缓存是本地内存， 无 Redis
 *       依赖）。
 * </ul>
 *
 * <p>完整 Key 只在方法内存活，用于缓存指纹查询与 Access 调用；不写入日志、指标或异常。
 */
@Component
public class OpenApiKeyAuthService {

  private static final Logger log = LoggerFactory.getLogger(OpenApiKeyAuthService.class);

  @Autowired private ApiKeyAuthCache cache;
  @Autowired private AccessApiKeyClient accessClient;

  /**
   * 鉴权完整 API Key。
   *
   * @param completeKey 完整 Key
   * @return 鉴权结果（不含完整 Key）
   */
  public CachedApiKey authenticate(String completeKey) {
    Optional<CachedApiKey> cached = cache.get(completeKey);
    if (cached.isPresent()) {
      return cached.get();
    }

    CachedApiKey fresh = accessClient.authenticate(completeKey);
    try {
      cache.put(completeKey, fresh);
    } catch (IllegalStateException e) {
      // event-before-put：失效事件先到达，拒绝写入旧版本。返回最新鉴权结果但不再缓存。
      log.debug("缓存写入被水位拒绝（失效事件先到达）— apiKeyId={}", fresh.apiKeyId());
    }
    return fresh;
  }
}
