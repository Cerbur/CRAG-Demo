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
import java.util.List;
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

  /**
   * 幂等确保 KnowledgeBase 在指定 Tenant 下存在 Scope（plan_21/21.2）。
   *
   * <p>语义：
   *
   * <ul>
   *   <li>Scope 不存在 → 校验调用方 {@code CREATE_KNOWLEDGE_BASE} 权限后创建 ACTIVE Scope；
   *   <li>Scope 存在且同 Tenant → 直接返回现有投影，不递增版本，不复活任何状态；
   *   <li>Scope 存在但不同 Tenant → 抛 {@link ScopeStateException}，保护租户隔离边界；
   *   <li>Scope 已 BLOCKED → 返回 BLOCKED 投影，终态不被任何补偿复活。
   * </ul>
   *
   * <p>本方法是 Console 建库部分成功兜底与 {@code KNOWLEDGE_BASE_CREATED} 事件消费的统一恢复入口；调用方 actorUserId 为 0
   * 时跳过实时权限校验（事件驱动场景），KnowledgeBase 归属由 Knowledge 决定。
   */
  @Transactional
  public ApiKeyScopeResult ensureScope(long actorUserId, long tenantId, long knowledgeBaseId) {
    // 人为调用（Console 兜底）须实时具备 CREATE_KNOWLEDGE_BASE；事件驱动场景 actorUserId=0 时跳过权限校验，
    // KnowledgeBase 归属由 Knowledge 决定，Access 只补齐授权投影。
    if (actorUserId != 0L) {
      requirePermission(actorUserId, tenantId, TenantAction.CREATE_KNOWLEDGE_BASE);
    }
    return scopeDao
        .findByKnowledgeBase(knowledgeBaseId)
        .map(
            existing -> {
              if (existing.getTenantId() != tenantId) {
                throw new ScopeStateException("knowledge base scope belongs to a different tenant");
              }
              return ApiKeyScopeResult.from(existing);
            })
        .orElseGet(
            () -> {
              ApiKeyScopeEntity scope = ApiKeyScopeEntity.create(knowledgeBaseId, tenantId);
              return ApiKeyScopeResult.from(scopeDao.insert(scope));
            });
  }

  /** 查询单条 Scope 投影（plan_21/21.2）。调用方须有 {@code MANAGE_API_KEY}；跨 Tenant 不泄漏存在性。 */
  @Transactional(readOnly = true)
  public ApiKeyScopeResult getScope(long actorUserId, long tenantId, long knowledgeBaseId) {
    requirePermission(actorUserId, tenantId, TenantAction.MANAGE_API_KEY);
    ApiKeyScopeEntity scope =
        scopeDao
            .findByKnowledgeBase(knowledgeBaseId)
            .filter(s -> s.getTenantId() == tenantId)
            .orElseThrow(ScopeBlockedException::new);
    return ApiKeyScopeResult.from(scope);
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
        key.getExpiresAt().toInstant(ZoneOffset.UTC),
        key.getVersion(),
        scope.getVersion());
  }

  /** 查询单条 Key 投影（plan_21/21.2）。调用方须有 {@code MANAGE_API_KEY}；跨 Tenant 不泄漏存在性。 */
  @Transactional(readOnly = true)
  public ApiKeyResult get(long actorUserId, long tenantId, long apiKeyId) {
    requirePermission(actorUserId, tenantId, TenantAction.MANAGE_API_KEY);
    return ApiKeyResult.from(requireKey(tenantId, apiKeyId));
  }

  /**
   * 按 KnowledgeBase 分页列出 Key 投影（plan_21/21.2）。调用方须有 {@code MANAGE_API_KEY}；游标分页稳定， pageToken
   * 为上一页最后一条 apiKeyId 的十进制字符串，为空表示从头开始。
   */
  @Transactional(readOnly = true)
  public ApiKeyListPage list(
      long actorUserId, long tenantId, long knowledgeBaseId, int pageSize, String pageToken) {
    requirePermission(actorUserId, tenantId, TenantAction.MANAGE_API_KEY);
    int limit = pageSize <= 0 ? 20 : Math.min(pageSize, 100);
    long after = parsePageToken(pageToken);
    // 多读一行用于判断是否还有下一页；按 tenantId 收敛后截取 limit。
    List<ApiKeyEntity> rows = apiKeyDao.findByKnowledgeBasePaged(knowledgeBaseId, after);
    List<ApiKeyResult> tenantRows =
        rows.stream().filter(k -> k.getTenantId() == tenantId).map(ApiKeyResult::from).toList();
    boolean hasMore = tenantRows.size() > limit;
    List<ApiKeyResult> items = tenantRows.stream().limit(limit).toList();
    String nextToken = hasMore ? Long.toString(items.get(items.size() - 1).apiKeyId()) : null;
    return new ApiKeyListPage(items, nextToken);
  }

  /** 解析分页游标；非法值统一视为从头开始，不向调用方泄漏格式错误。 */
  private static long parsePageToken(String pageToken) {
    if (pageToken == null || pageToken.isBlank()) {
      return 0L;
    }
    try {
      long parsed = Long.parseLong(pageToken.trim());
      return parsed < 0 ? 0L : parsed;
    } catch (NumberFormatException e) {
      return 0L;
    }
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
