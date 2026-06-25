package ai.cerbur.crag.knowledge.dao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.cerbur.crag.knowledge.dao.entity.DocumentEntity;
import ai.cerbur.crag.knowledge.dao.entity.FileObjectEntity;
import ai.cerbur.crag.knowledge.dao.entity.KnowledgeBaseEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

/**
 * Knowledge DAO 轻量组件测试：H2 下验证 insert/query、租户隔离、CAS 版本推进与 {@code updated_at} 行为。
 *
 * <p>H2 仅证明 DAO 行为与 Spring 装配，不表述为 PostgreSQL 方言或端到端兼容证明。
 */
@SpringBootTest
@Transactional
@DisplayName("Knowledge DAO")
class KnowledgeDaoComponentTest {

  @Autowired private KnowledgeBaseDao knowledgeBaseDao;
  @Autowired private DocumentDao documentDao;
  @Autowired private FileObjectDao fileObjectDao;

  @Test
  @DisplayName("KnowledgeBase insert 生成 ID、version=0、时间戳已设置；跨租户查询不可见")
  void knowledgeBaseInsertAndTenantIsolation() {
    KnowledgeBaseEntity kb =
        knowledgeBaseDao.insert(KnowledgeBaseEntity.create(101L, "kb-a", 200L));

    assertThat(kb.getKnowledgeBaseId()).isNotNull();
    assertThat(kb.getVersion()).isZero();
    assertThat(kb.getStatus()).isEqualTo(KnowledgeBaseEntity.STATUS_ACTIVE);
    assertThat(kb.getCreatedAt()).isNotNull();
    assertThat(kb.getUpdatedAt()).isNotNull();
    assertThat(knowledgeBaseDao.findByIdAndTenant(kb.getKnowledgeBaseId(), 101L)).isPresent();
    assertThat(knowledgeBaseDao.findByIdAndTenant(kb.getKnowledgeBaseId(), 999L))
        .as("跨租户查询不可见")
        .isEmpty();
  }

  @Test
  @DisplayName("Document/FileObject 同事务保存并按租户/文档查询")
  void documentAndFileObjectPersistAndQuery() {
    KnowledgeBaseEntity kb =
        knowledgeBaseDao.insert(KnowledgeBaseEntity.create(102L, "kb-b", 201L));
    DocumentEntity doc =
        documentDao.insert(
            DocumentEntity.create(
                kb.getKnowledgeBaseId(), 102L, 201L, "doc.txt", "TXT", 5L, "abc"));

    assertThat(doc.getDocId()).isNotNull();
    assertThat(doc.getVersion()).isZero();
    assertThat(doc.getOperationVersion()).isEqualTo(1L);
    assertThat(doc.getIngestionStatus()).isEqualTo(DocumentEntity.INGESTION_STATUS_PENDING);

    FileObjectEntity file =
        fileObjectDao.insert(FileObjectEntity.create(doc.getDocId(), "tenant/kb/doc", 5L, "abc"));
    assertThat(file.getFileObjectId()).isNotNull();
    assertThat(fileObjectDao.findByDocId(doc.getDocId())).isPresent();

    assertThat(documentDao.findByDocIdAndTenant(doc.getDocId(), 102L)).isPresent();
    assertThat(documentDao.findByDocIdAndTenant(doc.getDocId(), 999L)).as("跨租户查询不可见").isEmpty();
  }

  @Test
  @DisplayName("Document CAS 推进 version 与 updated_at；stale version 抛冲突")
  void documentCasUpdateAdvancesVersionAndTimestamp() {
    KnowledgeBaseEntity kb =
        knowledgeBaseDao.insert(KnowledgeBaseEntity.create(103L, "kb-c", 202L));
    DocumentEntity doc =
        documentDao.insert(
            DocumentEntity.create(
                kb.getKnowledgeBaseId(), 103L, 202L, "doc.md", "MARKDOWN", 7L, "def"));
    long docId = doc.getDocId();

    documentDao.updateIngestionStatus(docId, 103L, "PROCESSING", 0L);

    DocumentEntity refreshed = documentDao.findByDocIdAndTenant(docId, 103L).orElseThrow();
    assertThat(refreshed.getIngestionStatus()).isEqualTo("PROCESSING");
    assertThat(refreshed.getVersion()).isEqualTo(1L);
    assertThat(refreshed.getUpdatedAt()).isAfterOrEqualTo(doc.getUpdatedAt());

    assertThatThrownBy(() -> documentDao.updateIngestionStatus(docId, 103L, "READY", 0L))
        .isInstanceOf(DuplicateKeyException.class);
  }
}
