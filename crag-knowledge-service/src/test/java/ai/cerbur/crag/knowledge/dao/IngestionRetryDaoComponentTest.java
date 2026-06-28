package ai.cerbur.crag.knowledge.dao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.cerbur.crag.knowledge.dao.entity.DocumentEntity;
import ai.cerbur.crag.knowledge.dao.entity.KnowledgeBaseEntity;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * Document retry CAS 与滞留候选查询 DAO 组件测试（plan_21/21.5）。
 *
 * <p>H2 下验证：
 *
 * <ul>
 *   <li>{@link DocumentDao#retryIngestion} CAS 同时匹配 docId/tenantId/kbId/opVersion/version，递增
 *       opVersion/version， 重置 status=PENDING、清失败字段、写入新 attempt；
 *   <li>affected==0（opVersion/version/tenant/kb/docId 任一不符）抛 {@link VersionConflictException}；
 *   <li>{@link DocumentDao#findStaleIngestionCandidates} 按 PENDING/PROCESSING 滞留阈值返回候选。
 * </ul>
 *
 * <p>H2 仅证明 DAO 行为与 Spring 装配，不表述为 PostgreSQL 方言或端到端兼容证明。
 */
@SpringBootTest(classes = KnowledgeDaoTestConfig.class)
@Transactional
@DisplayName("Ingestion retry DAO")
class IngestionRetryDaoComponentTest {

  @Autowired private KnowledgeBaseDao knowledgeBaseDao;
  @Autowired private DocumentDao documentDao;

  private DocumentEntity seedDoc(long tenantId, long kbId) {
    knowledgeBaseDao.insert(KnowledgeBaseEntity.create(tenantId, "kb", 1L));
    return documentDao.insert(
        DocumentEntity.create(kbId, tenantId, 1L, "doc.txt", "TXT", 5L, "abc"));
  }

  private DocumentEntity markFailed(DocumentEntity doc, int attempt, String category) {
    documentDao.applyIngestionProjection(
        doc.getDocId(),
        doc.getTenantId(),
        doc.getKnowledgeBaseId(),
        doc.getOperationVersion(),
        doc.getVersion(),
        "FAILED",
        attempt,
        7001L,
        category,
        "msg",
        LocalDateTime.now().minusMinutes(5),
        LocalDateTime.now(),
        null);
    return documentDao.findByDocIdAndTenant(doc.getDocId(), doc.getTenantId()).orElseThrow();
  }

  @Test
  @DisplayName("retryIngestion CAS 成功：opVersion/attempt 递增，status=PENDING，失败字段清空")
  void retryCasAdvancesVersionAndClearsFailureFields() {
    DocumentEntity doc = markFailed(seedDoc(501L, 601L), 1, "INDEX_TRANSIENT_FAILURE");
    long currentOpVersion = doc.getOperationVersion();
    long currentVersion = doc.getVersion();

    documentDao.retryIngestion(
        doc.getDocId(), 501L, 601L, currentOpVersion, currentVersion, 2, currentOpVersion + 1);

    DocumentEntity refreshed = documentDao.findByDocIdAndTenant(doc.getDocId(), 501L).orElseThrow();
    assertThat(refreshed.getIngestionStatus()).isEqualTo("PENDING");
    assertThat(refreshed.getOperationVersion()).isEqualTo(currentOpVersion + 1);
    assertThat(refreshed.getIngestionAttempt()).isEqualTo(2);
    assertThat(refreshed.getVersion()).isEqualTo(currentVersion + 1);
    assertThat(refreshed.getFailureCategory()).isNull();
    assertThat(refreshed.getFailureMessage()).isNull();
    assertThat(refreshed.getIngestionJobId()).isNull();
    assertThat(refreshed.getStartedAt()).isNull();
    assertThat(refreshed.getCompletedAt()).isNull();
    assertThat(refreshed.getNextRetryAt()).isNull();
  }

  @Test
  @DisplayName("retryIngestion CAS 失败：version 不匹配抛 VersionConflictException")
  void retryCasVersionMismatchThrows() {
    DocumentEntity doc = markFailed(seedDoc(502L, 602L), 1, "PROCESSING_TIMEOUT");

    assertThatThrownBy(
            () ->
                documentDao.retryIngestion(
                    doc.getDocId(),
                    502L,
                    602L,
                    doc.getOperationVersion(),
                    doc.getVersion() + 999L,
                    2,
                    doc.getOperationVersion() + 1))
        .isInstanceOf(VersionConflictException.class);
  }

  @Test
  @DisplayName("retryIngestion CAS 失败：opVersion 不匹配抛 VersionConflictException")
  void retryCasOpVersionMismatchThrows() {
    DocumentEntity doc = markFailed(seedDoc(503L, 603L), 1, "PROCESSING_TIMEOUT");

    assertThatThrownBy(
            () ->
                documentDao.retryIngestion(
                    doc.getDocId(),
                    503L,
                    603L,
                    doc.getOperationVersion() + 999L,
                    doc.getVersion(),
                    2,
                    doc.getOperationVersion() + 1))
        .isInstanceOf(VersionConflictException.class);
  }

  @Test
  @DisplayName("retryIngestion CAS 失败：tenant 不匹配抛 VersionConflictException")
  void retryCasTenantMismatchThrows() {
    DocumentEntity doc = markFailed(seedDoc(504L, 604L), 1, "PROCESSING_TIMEOUT");

    assertThatThrownBy(
            () ->
                documentDao.retryIngestion(
                    doc.getDocId(),
                    999L,
                    604L,
                    doc.getOperationVersion(),
                    doc.getVersion(),
                    2,
                    doc.getOperationVersion() + 1))
        .isInstanceOf(VersionConflictException.class);
  }

  @Test
  @DisplayName("findStaleIngestionCandidates：PENDING 超过阈值返回候选")
  void findStaleCandidatesReturnsPendingBeyondThreshold() {
    DocumentEntity pending = seedDoc(505L, 605L);
    // PENDING 文档 updatedAt 为创建时间（now），阈值设为未来时间使其滞留。
    LocalDateTime futureThreshold = LocalDateTime.now().plusMinutes(10);

    var candidates = documentDao.findStaleIngestionCandidates(futureThreshold, futureThreshold, 10);

    assertThat(candidates.getContent()).anyMatch(d -> d.getDocId().equals(pending.getDocId()));
  }

  @Test
  @DisplayName("findStaleIngestionCandidates：READY 文档不被视为滞留")
  void findStaleCandidatesExcludesReady() {
    DocumentEntity doc = seedDoc(506L, 606L);
    documentDao.applyIngestionProjection(
        doc.getDocId(),
        506L,
        606L,
        doc.getOperationVersion(),
        doc.getVersion(),
        "READY",
        1,
        7002L,
        null,
        null,
        LocalDateTime.now(),
        LocalDateTime.now(),
        null);
    LocalDateTime futureThreshold = LocalDateTime.now().plusMinutes(10);

    var candidates = documentDao.findStaleIngestionCandidates(futureThreshold, futureThreshold, 10);

    assertThat(candidates.getContent()).noneMatch(d -> d.getDocId().equals(doc.getDocId()));
  }
}
