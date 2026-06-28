package ai.cerbur.crag.access.dao;

import ai.cerbur.crag.access.dao.entity.ApiKeyEntity;
import ai.cerbur.crag.access.dao.repository.ApiKeyRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * api_key 数据库访问边界，只依赖 {@link ApiKeyRepository}。
 *
 * <p>按可检索前缀定位 Key；状态变更通过版本 CAS 推进，affected rows 为零抛 {@link VersionConflictException}。Scope Block
 * 批量禁用其全部有效 Key 为受控批量更新。
 */
@Component
public class ApiKeyDao {

  @Autowired private ApiKeyRepository apiKeyRepository;

  /** 插入 Key；ID 由 Service 层分配，完整秘密只在调用方持有。 */
  public ApiKeyEntity insert(ApiKeyEntity entity) {
    return apiKeyRepository.save(entity);
  }

  public Optional<ApiKeyEntity> findByPrefix(String keyPrefix) {
    return apiKeyRepository.findByKeyPrefix(keyPrefix);
  }

  public Optional<ApiKeyEntity> findById(long apiKeyId) {
    return apiKeyRepository.findById(apiKeyId);
  }

  public List<ApiKeyEntity> findByKnowledgeBase(long knowledgeBaseId) {
    return apiKeyRepository.findByKnowledgeBaseId(knowledgeBaseId);
  }

  /** 按 KnowledgeBase 游标分页（plan_21/21.2）；调用方按业务校验 actor/tenant 后传入。 */
  public List<ApiKeyEntity> findByKnowledgeBasePaged(long knowledgeBaseId, long apiKeyIdAfter) {
    return apiKeyRepository.findByKnowledgeBaseIdAndApiKeyIdGreaterThanOrderByApiKeyIdAsc(
        knowledgeBaseId, apiKeyIdAfter);
  }

  /**
   * Key 状态版本 CAS 更新。
   *
   * @return 更新后重新读取的 Key
   * @throws VersionConflictException 版本不匹配（affected rows 为零）
   */
  public ApiKeyEntity updateStatus(
      long apiKeyId,
      long expectedVersion,
      String status,
      LocalDateTime disabledAt,
      LocalDateTime revokedAt) {
    int affected =
        apiKeyRepository.updateStatus(
            apiKeyId, expectedVersion, status, disabledAt, revokedAt, LocalDateTime.now());
    if (affected == 0) {
      throw new VersionConflictException(
          "api_key status CAS failed: apiKeyId=" + apiKeyId + " version=" + expectedVersion);
    }
    return apiKeyRepository
        .findById(apiKeyId)
        .orElseThrow(
            () -> new IllegalStateException("api_key vanished after CAS: apiKeyId=" + apiKeyId));
  }

  /** Scope Block 批量禁用 KnowledgeBase 内全部 ACTIVE Key；返回受影响行数。 */
  public int disableActiveByKnowledgeBase(long knowledgeBaseId) {
    return apiKeyRepository.disableActiveByKnowledgeBase(
        knowledgeBaseId,
        ApiKeyEntity.STATUS_ACTIVE,
        ApiKeyEntity.STATUS_DISABLED,
        LocalDateTime.now());
  }

  public void updateLastUsed(long apiKeyId) {
    apiKeyRepository.updateLastUsed(apiKeyId, LocalDateTime.now());
  }
}
