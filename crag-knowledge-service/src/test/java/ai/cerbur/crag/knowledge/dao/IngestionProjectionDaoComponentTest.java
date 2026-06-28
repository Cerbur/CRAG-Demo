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
 * Document 摄取投影 DAO 组件测试（plan_21/21.3）。
 *
 * <p>H2 下验证：
 *
 * <ul>
 *   <li>新增投影列 {@code ingestion_attempt / ingestion_job_id / failure_category / failure_message /
 *       started_at / completed_at / next_retry_at} 存在且 nullable；
 *   <li>{@link DocumentDao#applyIngestionProjection} CAS 同时匹配 docId、tenantId、
 *       knowledgeBaseId、operationVersion、version，推进 status 与 version；
 *   <li>affected==0（operationVersion、version、tenant、kb 任一不符）抛 {@link
 *       ai.cerbur.crag.knowledge.dao.VersionConflictException}。
 * </ul>
 *
 * <p>H2 仅证明 DAO 行为与 Spring 装配，不表述为 PostgreSQL 方言或端到端兼容证明。
 */
@SpringBootTest(classes = KnowledgeDaoTestConfig.class)
@Transactional
@DisplayName("Ingestion projection DAO")
class IngestionProjectionDaoComponentTest {

  @Autowired private KnowledgeBaseDao knowledgeBaseDao;
  @Autowired private DocumentDao documentDao;

  private DocumentEntity seedDoc(long tenantId, long kbId) {
    knowledgeBaseDao.insert(KnowledgeBaseEntity.create(tenantId, "kb", 1L));
    return documentDao.insert(
        DocumentEntity.create(kbId, tenantId, 1L, "doc.txt", "TXT", 5L, "abc"));
  }

  @Test
  @DisplayName("新投影列 nullable：PENDING 文档投影字段为 null/默认值")
  void newProjectionColumnsNullable() {
    DocumentEntity doc = seedDoc(301L, 401L);

    DocumentEntity refreshed = documentDao.findByDocIdAndTenant(doc.getDocId(), 301L).orElseThrow();

    assertThat(refreshed.getOperationVersion()).isEqualTo(1L);
    assertThat(refreshed.getIngestionStatus()).isEqualTo("PENDING");
    assertThat(refreshed.getIngestionAttempt()).isZero();
    assertThat(refreshed.getIngestionJobId()).isNull();
    assertThat(refreshed.getFailureCategory()).isNull();
    assertThat(refreshed.getFailureMessage()).isNull();
    assertThat(refreshed.getStartedAt()).isNull();
    assertThat(refreshed.getCompletedAt()).isNull();
    assertThat(refreshed.getNextRetryAt()).isNull();
  }

  @Test
  @DisplayName(
      "applyIngestionProjection CAS 推进 PROCESSING：status/version/attempt/jobId/startedAt 更新")
  void applyProcessingAdvancesStatusVersionAttempt() {
    DocumentEntity doc = seedDoc(302L, 402L);
    LocalDateTime startedAt = LocalDateTime.now();

    int affected =
        documentDao.applyIngestionProjection(
            doc.getDocId(),
            302L,
            402L,
            1L,
            0L,
            "PROCESSING",
            1,
            7001L,
            null,
            null,
            startedAt,
            null,
            null);

    assertThat(affected).isEqualTo(1);
    DocumentEntity refreshed = documentDao.findByDocIdAndTenant(doc.getDocId(), 302L).orElseThrow();
    assertThat(refreshed.getIngestionStatus()).isEqualTo("PROCESSING");
    assertThat(refreshed.getVersion()).isEqualTo(1L);
    assertThat(refreshed.getIngestionAttempt()).isEqualTo(1);
    assertThat(refreshed.getIngestionJobId()).isEqualTo(7001L);
    assertThat(refreshed.getStartedAt()).isEqualTo(startedAt);
    assertThat(refreshed.getCompletedAt()).isNull();
  }

  @Test
  @DisplayName("applyIngestionProjection 写入 FAILED 失败字段并 completedAt")
  void applyFailedRecordsFailureFields() {
    DocumentEntity doc = seedDoc(303L, 403L);
    LocalDateTime now = LocalDateTime.now();

    documentDao.applyIngestionProjection(
        doc.getDocId(),
        303L,
        403L,
        1L,
        0L,
        "FAILED",
        2,
        7002L,
        "CHECKSUM_MISMATCH",
        "checksum mismatch",
        now,
        now,
        null);

    DocumentEntity refreshed = documentDao.findByDocIdAndTenant(doc.getDocId(), 303L).orElseThrow();
    assertThat(refreshed.getIngestionStatus()).isEqualTo("FAILED");
    assertThat(refreshed.getFailureCategory()).isEqualTo("CHECKSUM_MISMATCH");
    assertThat(refreshed.getFailureMessage()).isEqualTo("checksum mismatch");
    assertThat(refreshed.getCompletedAt()).isEqualTo(now);
  }

  @Test
  @DisplayName("applyIngestionProjection WHERE 含 version：version 不符抛 VersionConflictException")
  void versionMismatchThrowsVersionConflict() {
    DocumentEntity doc = seedDoc(304L, 404L);

    assertThatThrownBy(
            () ->
                documentDao.applyIngestionProjection(
                    doc.getDocId(),
                    304L,
                    404L,
                    1L,
                    999L, // stale version
                    "PROCESSING",
                    1,
                    7L,
                    null,
                    null,
                    null,
                    null,
                    null))
        .isInstanceOf(VersionConflictException.class);
  }

  @Test
  @DisplayName("applyIngestionProjection WHERE 含 tenant：跨租户冲突抛 VersionConflictException")
  void tenantMismatchThrowsVersionConflict() {
    DocumentEntity doc = seedDoc(305L, 405L);

    assertThatThrownBy(
            () ->
                documentDao.applyIngestionProjection(
                    doc.getDocId(),
                    999L, // wrong tenant
                    405L,
                    1L,
                    0L,
                    "PROCESSING",
                    1,
                    7L,
                    null,
                    null,
                    null,
                    null,
                    null))
        .isInstanceOf(VersionConflictException.class);
  }

  @Test
  @DisplayName("applyIngestionProjection WHERE 含 operationVersion：旧版本抛 VersionConflictException")
  void operationVersionMismatchThrowsVersionConflict() {
    DocumentEntity doc = seedDoc(306L, 406L);

    assertThatThrownBy(
            () ->
                documentDao.applyIngestionProjection(
                    doc.getDocId(),
                    306L,
                    406L,
                    999L, // wrong operationVersion
                    0L,
                    "PROCESSING",
                    1,
                    7L,
                    null,
                    null,
                    null,
                    null,
                    null))
        .isInstanceOf(VersionConflictException.class);
  }

  @Test
  @DisplayName("applyIngestionProjection WHERE 含 knowledgeBaseId：kb 不符抛 VersionConflictException")
  void knowledgeBaseMismatchThrowsVersionConflict() {
    DocumentEntity doc = seedDoc(307L, 407L);

    assertThatThrownBy(
            () ->
                documentDao.applyIngestionProjection(
                    doc.getDocId(),
                    307L,
                    999L, // wrong kb
                    1L,
                    0L,
                    "PROCESSING",
                    1,
                    7L,
                    null,
                    null,
                    null,
                    null,
                    null))
        .isInstanceOf(VersionConflictException.class);
  }
}
