package ai.cerbur.crag.access.dao;

import ai.cerbur.crag.access.dao.entity.ApiKeyScopeEntity;
import ai.cerbur.crag.access.dao.repository.ApiKeyScopeRepository;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * api_key_scope 数据库访问边界，只依赖 {@link ApiKeyScopeRepository}。
 *
 * <p>终态阻塞通过版本 CAS 推进；affected rows 为零抛 {@link VersionConflictException}。
 */
@Component
public class ApiKeyScopeDao {

  @Autowired private ApiKeyScopeRepository apiKeyScopeRepository;

  /** 插入 Scope；主键即 knowledge_base_id。 */
  public ApiKeyScopeEntity insert(ApiKeyScopeEntity entity) {
    return apiKeyScopeRepository.save(entity);
  }

  public Optional<ApiKeyScopeEntity> findByKnowledgeBase(long knowledgeBaseId) {
    return apiKeyScopeRepository.findByKnowledgeBaseId(knowledgeBaseId);
  }

  /**
   * 终态阻塞版本 CAS：ACTIVE→BLOCKED。
   *
   * @throws VersionConflictException 版本/状态不匹配（affected rows 为零）
   */
  public void block(long knowledgeBaseId, long expectedVersion) {
    int affected =
        apiKeyScopeRepository.block(
            knowledgeBaseId,
            expectedVersion,
            ApiKeyScopeEntity.STATUS_ACTIVE,
            ApiKeyScopeEntity.STATUS_BLOCKED,
            java.time.LocalDateTime.now());
    if (affected == 0) {
      throw new VersionConflictException(
          "api_key_scope block CAS failed: knowledgeBaseId="
              + knowledgeBaseId
              + " version="
              + expectedVersion);
    }
  }
}
