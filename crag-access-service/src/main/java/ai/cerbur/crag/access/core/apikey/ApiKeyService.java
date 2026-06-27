package ai.cerbur.crag.access.core.apikey;

import ai.cerbur.crag.access.core.membership.AuthorizationRequest;
import ai.cerbur.crag.access.core.membership.MembershipAuthorizationException;
import ai.cerbur.crag.access.core.membership.MembershipService;
import ai.cerbur.crag.access.core.membership.TenantAction;
import ai.cerbur.crag.access.dao.ApiKeyDao;
import ai.cerbur.crag.access.dao.ApiKeyScopeDao;
import ai.cerbur.crag.access.dao.entity.ApiKeyEntity;
import ai.cerbur.crag.access.dao.entity.ApiKeyScopeEntity;
import ai.cerbur.crag.access.producer.AccessEventTypes;
import ai.cerbur.crag.access.producer.ApiKeyInvalidatedPayload;
import ai.cerbur.crag.access.producer.ApiKeyInvalidationOutboxWriter;
import ai.cerbur.crag.access.security.AccessSecurityConfiguration;
import ai.cerbur.crag.access.security.SecretGenerator;
import ai.cerbur.crag.access.security.SecretHmac;
import ai.cerbur.crag.id.api.CragIdGenerator;
import ai.cerbur.crag.id.api.IdEntityType;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scope 与 API Key 用例：注册/终态阻塞 Scope，单 Key 创建/鉴权/停用/启用/轮换/吊销与过期判断。
 *
 * <p>管理动作先实时授权调用方为对应 Tenant OWNER；完整 Key 只在创建/轮换成功时返回一次，永不落库。鉴权先按前缀定位，再以 API Key 专用 Pepper 计算 HMAC
 * 恒定时间比较，并检查 Key、Scope 状态与到期。失效事件生产由 20.7 接入。
 */
@Service
public class ApiKeyService {

  private static final int MAX_PREFIX_RETRIES = 3;

  @Autowired private ApiKeyDao apiKeyDao;
  @Autowired private ApiKeyScopeDao scopeDao;
  @Autowired private MembershipService membershipService;
  @Autowired private CragIdGenerator idGenerator;
  @Autowired private SecretGenerator secretGenerator;

  @Autowired
  @Qualifier(AccessSecurityConfiguration.API_KEY_HMAC)
  private SecretHmac apiKeyHmac;

  @Autowired private ApiKeyInvalidationOutboxWriter invalidationWriter;

  /** 注册 KnowledgeBase 授权投影。调用方须有创建 KnowledgeBase 权限。 */
  @Transactional
  public ApiKeyScopeResult registerScope(long actorUserId, long tenantId, long knowledgeBaseId) {
    requirePermission(actorUserId, tenantId, TenantAction.CREATE_KNOWLEDGE_BASE);
    ApiKeyScopeEntity scope = ApiKeyScopeEntity.create(knowledgeBaseId, tenantId);
    return ApiKeyScopeResult.from(scopeDao.insert(scope));
  }

  /** 终态阻塞 Scope，同事务禁用其全部有效 Key。 */
  @Transactional
  public ApiKeyScopeResult blockScope(long actorUserId, long tenantId, long knowledgeBaseId) {
    requirePermission(actorUserId, tenantId, TenantAction.MANAGE_API_KEY);
    ApiKeyScopeEntity scope =
        scopeDao.findByKnowledgeBase(knowledgeBaseId).orElseThrow(ScopeBlockedException::new);
    if (ApiKeyScopeEntity.STATUS_BLOCKED.equals(scope.getStatus())) {
      return ApiKeyScopeResult.from(scope);
    }
    scopeDao.block(knowledgeBaseId, scope.getVersion());
    apiKeyDao.disableActiveByKnowledgeBase(knowledgeBaseId);
    ApiKeyScopeEntity blocked = scopeDao.findByKnowledgeBase(knowledgeBaseId).orElseThrow();
    invalidationWriter.write(
        new ApiKeyInvalidatedPayload(
            AccessEventTypes.RESOURCE_API_KEY_SCOPE,
            knowledgeBaseId,
            scope.getTenantId(),
            knowledgeBaseId,
            "SCOPE_BLOCKED",
            blocked.getVersion()),
        "scope-" + knowledgeBaseId,
        Instant.now());
    return ApiKeyScopeResult.from(blocked);
  }

  /** 创建 API Key，只返回一次完整 Key。 */
  @Transactional
  public CreatedApiKey create(
      long actorUserId, long tenantId, long knowledgeBaseId, String name, Duration ttl) {
    requirePermission(actorUserId, tenantId, TenantAction.MANAGE_API_KEY);
    requireActiveScope(knowledgeBaseId);
    String validatedName = ApiKeyPolicy.validateName(name);
    Duration resolvedTtl = ApiKeyPolicy.resolveTtl(ttl);
    return persistNewKey(tenantId, knowledgeBaseId, validatedName, resolvedTtl, null);
  }

  /** 停用 Key：ACTIVE→DISABLED，同事务写失效事件。 */
  @Transactional
  public ApiKeyResult disable(long actorUserId, long tenantId, long apiKeyId) {
    requirePermission(actorUserId, tenantId, TenantAction.MANAGE_API_KEY);
    ApiKeyEntity key = requireKey(tenantId, apiKeyId);
    if (!ApiKeyEntity.STATUS_ACTIVE.equals(key.getStatus())) {
      throw new ApiKeyStateException("api key is not active");
    }
    ApiKeyEntity updated =
        apiKeyDao.updateStatus(
            apiKeyId, key.getVersion(), ApiKeyEntity.STATUS_DISABLED, LocalDateTime.now(), null);
    writeKeyEvent(key, "DISABLED", updated.getVersion());
    return ApiKeyResult.from(updated);
  }

  /** 启用 Key：DISABLED→ACTIVE，同事务写失效事件。 */
  @Transactional
  public ApiKeyResult enable(long actorUserId, long tenantId, long apiKeyId) {
    requirePermission(actorUserId, tenantId, TenantAction.MANAGE_API_KEY);
    ApiKeyEntity key = requireKey(tenantId, apiKeyId);
    if (!ApiKeyEntity.STATUS_DISABLED.equals(key.getStatus())) {
      throw new ApiKeyStateException("api key is not disabled");
    }
    ApiKeyEntity updated =
        apiKeyDao.updateStatus(apiKeyId, key.getVersion(), ApiKeyEntity.STATUS_ACTIVE, null, null);
    writeKeyEvent(key, "ENABLED", updated.getVersion());
    return ApiKeyResult.from(updated);
  }

  /** 吊销 Key：ACTIVE/DISABLED→REVOKED，终态，同事务写失效事件。 */
  @Transactional
  public ApiKeyResult revoke(long actorUserId, long tenantId, long apiKeyId) {
    requirePermission(actorUserId, tenantId, TenantAction.MANAGE_API_KEY);
    ApiKeyEntity key = requireKey(tenantId, apiKeyId);
    if (ApiKeyEntity.STATUS_REVOKED.equals(key.getStatus())) {
      throw new ApiKeyStateException("api key already revoked");
    }
    ApiKeyEntity updated =
        apiKeyDao.updateStatus(
            apiKeyId, key.getVersion(), ApiKeyEntity.STATUS_REVOKED, null, LocalDateTime.now());
    writeKeyEvent(key, "REVOKED", updated.getVersion());
    return ApiKeyResult.from(updated);
  }

  /** 轮换 Key：同事务创建新 ACTIVE Key 并吊销旧 Key，只返回一次新秘密；为旧 Key 写失效事件。 */
  @Transactional
  public CreatedApiKey rotate(long actorUserId, long tenantId, long apiKeyId, Duration ttl) {
    requirePermission(actorUserId, tenantId, TenantAction.MANAGE_API_KEY);
    ApiKeyEntity key = requireKey(tenantId, apiKeyId);
    if (ApiKeyEntity.STATUS_REVOKED.equals(key.getStatus())) {
      throw new ApiKeyStateException("cannot rotate a revoked api key");
    }
    Duration resolvedTtl = ApiKeyPolicy.resolveTtl(ttl);
    CreatedApiKey created =
        persistNewKey(tenantId, key.getKnowledgeBaseId(), key.getName(), resolvedTtl, apiKeyId);
    ApiKeyEntity revoked =
        apiKeyDao.updateStatus(
            apiKeyId, key.getVersion(), ApiKeyEntity.STATUS_REVOKED, null, LocalDateTime.now());
    writeKeyEvent(key, "ROTATED", revoked.getVersion());
    return created;
  }

  /** 鉴权完整 Key：成功只返回定位与过期信息，失败统一为凭据无效。 */
  @Transactional
  public AuthenticatedApiKey authenticate(String completeKey) {
    ApiKeyPolicy.ParsedKey parsed;
    try {
      parsed = ApiKeyPolicy.parseCompleteKey(completeKey);
    } catch (IllegalArgumentException e) {
      throw new ApiKeyNotFoundException();
    }
    ApiKeyEntity key =
        apiKeyDao.findByPrefix(parsed.prefix()).orElseThrow(ApiKeyNotFoundException::new);
    if (!ApiKeyEntity.STATUS_ACTIVE.equals(key.getStatus())) {
      throw new ApiKeyNotFoundException();
    }
    if (key.getExpiresAt().isBefore(LocalDateTime.now())) {
      throw new ApiKeyNotFoundException();
    }
    ApiKeyScopeEntity scope =
        scopeDao
            .findByKnowledgeBase(key.getKnowledgeBaseId())
            .orElseThrow(ApiKeyNotFoundException::new);
    if (!ApiKeyScopeEntity.STATUS_ACTIVE.equals(scope.getStatus())) {
      throw new ApiKeyNotFoundException();
    }
    if (!apiKeyHmac.matches(completeKey, key.getSecretHmac())) {
      throw new ApiKeyNotFoundException();
    }
    apiKeyDao.updateLastUsed(key.getApiKeyId());
    return new AuthenticatedApiKey(
        key.getApiKeyId(),
        key.getTenantId(),
        key.getKnowledgeBaseId(),
        key.getExpiresAt().toInstant(ZoneOffset.UTC));
  }

  /** 生成唯一前缀并写入新 ACTIVE Key。前缀冲突最多重试 3 次。 */
  private CreatedApiKey persistNewKey(
      long tenantId, long knowledgeBaseId, String name, Duration ttl, Long rotatedFrom) {
    for (int attempt = 0; attempt < MAX_PREFIX_RETRIES; attempt++) {
      String prefix = secretGenerator.randomAlphanumeric(ApiKeyPolicy.PREFIX_LENGTH);
      if (apiKeyDao.findByPrefix(prefix).isPresent()) {
        continue;
      }
      String secret = secretGenerator.randomBase64Url(ApiKeyPolicy.SECRET_BYTES);
      String completeKey = ApiKeyPolicy.buildCompleteKey(prefix, secret);
      String secretHmac = apiKeyHmac.digest(completeKey);
      LocalDateTime expiresAt = LocalDateTime.now().plus(ttl);
      long apiKeyId = idGenerator.nextId(IdEntityType.API_KEY);
      ApiKeyEntity entity =
          ApiKeyEntity.create(
              apiKeyId, tenantId, knowledgeBaseId, name, prefix, secretHmac, 0L, expiresAt);
      entity.setRotatedFrom(rotatedFrom);
      apiKeyDao.insert(entity);
      return new CreatedApiKey(
          apiKeyId,
          tenantId,
          knowledgeBaseId,
          name,
          completeKey,
          expiresAt.toInstant(ZoneOffset.UTC));
    }
    throw new IllegalStateException("failed to generate a unique api key prefix after retries");
  }

  private void requirePermission(long actorUserId, long tenantId, TenantAction action) {
    if (!membershipService
        .authorize(new AuthorizationRequest(actorUserId, tenantId, action, null))
        .allowed()) {
      throw new MembershipAuthorizationException();
    }
  }

  private void requireActiveScope(long knowledgeBaseId) {
    ApiKeyScopeEntity scope =
        scopeDao.findByKnowledgeBase(knowledgeBaseId).orElseThrow(ScopeBlockedException::new);
    if (!ApiKeyScopeEntity.STATUS_ACTIVE.equals(scope.getStatus())) {
      throw new ScopeBlockedException();
    }
  }

  private ApiKeyEntity requireKey(long tenantId, long apiKeyId) {
    return apiKeyDao
        .findById(apiKeyId)
        .filter(k -> k.getTenantId() == tenantId)
        .orElseThrow(ApiKeyNotFoundException::new);
  }

  /** 写入单 Key 失效事件；payload 只含定位与版本，不含完整 Key 或 HMAC。 */
  private void writeKeyEvent(ApiKeyEntity key, String action, long version) {
    invalidationWriter.write(
        new ApiKeyInvalidatedPayload(
            AccessEventTypes.RESOURCE_API_KEY,
            key.getApiKeyId(),
            key.getTenantId(),
            key.getKnowledgeBaseId(),
            action,
            version),
        "apikey-" + key.getApiKeyId(),
        Instant.now());
  }
}
