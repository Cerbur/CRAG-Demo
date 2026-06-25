package ai.cerbur.crag.knowledge.core.document;

import ai.cerbur.crag.knowledge.dao.DocumentDao;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Document 查询用例：按文档+租户查看、按知识库+租户列表。查询均携带 {@code tenantId} 保证租户隔离。 */
@Service
public class DocumentQueryService {

  @Autowired private DocumentDao documentDao;

  /** 按文档 ID 与租户查看；跨租户返回空。 */
  @Transactional(readOnly = true)
  public Optional<DocumentResult> get(long docId, long tenantId) {
    return documentDao.findByDocIdAndTenant(docId, tenantId).map(DocumentResult::from);
  }

  /** 按知识库与租户分页列表。 */
  @Transactional(readOnly = true)
  public List<DocumentResult> list(long knowledgeBaseId, long tenantId, Pageable pageable) {
    return documentDao.listByKnowledgeBaseAndTenant(knowledgeBaseId, tenantId, pageable).stream()
        .map(DocumentResult::from)
        .toList();
  }
}
