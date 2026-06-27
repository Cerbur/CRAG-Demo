package ai.cerbur.crag.access.dao.repository;

import ai.cerbur.crag.access.dao.entity.ApiKeyEntity;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * api_key Spring Data Repository，仅允许 {@code ai.cerbur.crag.access.dao} 包调用。
 *
 * <p>按可检索前缀定位 Key；状态变更通过版本 CAS 推进，Scope Block 时批量禁用其全部有效 Key（受控批量，非逐条 CAS）。
 */
@Repository
public interface ApiKeyRepository extends JpaRepository<ApiKeyEntity, Long> {

  Optional<ApiKeyEntity> findByKeyPrefix(String keyPrefix);

  List<ApiKeyEntity> findByKnowledgeBaseId(long knowledgeBaseId);

  /** Key 状态版本 CAS 更新；返回 affected rows。 */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      "UPDATE ApiKeyEntity k SET k.status = :status, k.disabledAt = :disabledAt, "
          + "k.revokedAt = :revokedAt, k.version = k.version + 1, k.updatedAt = :now "
          + "WHERE k.apiKeyId = :apiKeyId AND k.version = :expectedVersion")
  int updateStatus(
      @Param("apiKeyId") long apiKeyId,
      @Param("expectedVersion") long expectedVersion,
      @Param("status") String status,
      @Param("disabledAt") LocalDateTime disabledAt,
      @Param("revokedAt") LocalDateTime revokedAt,
      @Param("now") LocalDateTime now);

  /** Scope Block 批量禁用 KnowledgeBase 内全部 ACTIVE Key；返回 affected rows。 */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      "UPDATE ApiKeyEntity k SET k.status = :disabled, k.disabledAt = :now, "
          + "k.version = k.version + 1, k.updatedAt = :now "
          + "WHERE k.knowledgeBaseId = :knowledgeBaseId AND k.status = :active")
  int disableActiveByKnowledgeBase(
      @Param("knowledgeBaseId") long knowledgeBaseId,
      @Param("active") String activeStatus,
      @Param("disabled") String disabledStatus,
      @Param("now") LocalDateTime now);

  /** 更新最后使用时间，不计入业务版本。 */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query("UPDATE ApiKeyEntity k SET k.lastUsedAt = :now WHERE k.apiKeyId = :apiKeyId")
  int updateLastUsed(@Param("apiKeyId") long apiKeyId, @Param("now") LocalDateTime now);
}
