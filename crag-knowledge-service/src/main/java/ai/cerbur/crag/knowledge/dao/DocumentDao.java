package ai.cerbur.crag.knowledge.dao;

import ai.cerbur.crag.knowledge.dao.entity.DocumentEntity;
import ai.cerbur.crag.knowledge.dao.repository.DocumentRepository;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

/**
 * Document 数据库访问边界，只依赖 {@link DocumentRepository}。
 *
 * <p>查询均携带 {@code tenantId}；{@link #updateIngestionStatus} 是 CAS 状态推进原语，{@code affected == 0}
 * 表示版本已被 其他实例变更，抛出 {@link DuplicateKeyException} 由调用方按版本冲突语义处理。
 */
@Component
public class DocumentDao {

  @Autowired private DocumentRepository documentRepository;

  /** 插入文档；ID 由数据库 identity 列生成并回填。 */
  public DocumentEntity insert(DocumentEntity entity) {
    return documentRepository.save(entity);
  }

  /** 按文档 ID 与租户查询；跨租户返回空。 */
  public Optional<DocumentEntity> findByDocIdAndTenant(long docId, long tenantId) {
    return documentRepository.findByDocIdAndTenantId(docId, tenantId);
  }

  /** 按知识库与租户分页列表。 */
  public Page<DocumentEntity> listByKnowledgeBaseAndTenant(
      long knowledgeBaseId, long tenantId, Pageable pageable) {
    return documentRepository.findByKnowledgeBaseIdAndTenantId(knowledgeBaseId, tenantId, pageable);
  }

  /**
   * CAS 推进 ingestion_status；带版本条件并在数据库侧递增 version。
   *
   * @return affected rows（始终 ≥ 1）
   * @throws DuplicateKeyException 当 {@code affected == 0}（版本冲突，另一实例已接管）
   */
  public int updateIngestionStatus(long docId, long tenantId, String newStatus, Long version) {
    int affected = documentRepository.updateIngestionStatus(docId, tenantId, newStatus, version);
    if (affected == 0) {
      throw new DuplicateKeyException(
          "CAS updateIngestionStatus failed: doc "
              + docId
              + " version "
              + version
              + " already stale");
    }
    return affected;
  }
}
