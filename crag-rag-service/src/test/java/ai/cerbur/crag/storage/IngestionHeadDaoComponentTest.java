package ai.cerbur.crag.storage;

import static org.assertj.core.api.Assertions.assertThat;

import ai.cerbur.crag.storage.result.IngestionHead;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * IngestionHeadDao 轻量组件测试（Plan 21.4）：H2 下验证 head 幂等创建、CAS 单调推进与版本读取.
 *
 * <p>验证不变量：head.operationVersion 单调递增；低版本/同版本 advance 返回 0（幂等 ACK）；高版本 CAS 成功.
 *
 * <p>H2 仅证明 DAO 行为与 Spring 装配，不表述为 PostgreSQL 方言或端到端兼容证明.
 */
@SpringBootTest(classes = IngestionJobDaoTestConfig.class)
@Transactional
@DisplayName("IngestionHeadDao 单调推进与幂等 ACK")
class IngestionHeadDaoComponentTest {

  @Autowired private IngestionHeadDao ingestionHeadDao;
  @Autowired private IngestionJobDao ingestionJobDao;

  @Nested
  @DisplayName("幂等创建与查询")
  class FindOrCreate {

    @Test
    @DisplayName("首次创建 → 持久化 knowledgeBaseId/docId/operationVersion，版本 0")
    void createsHeadWithInitialVersion() {
      IngestionHead head = ingestionHeadDao.findOrCreate(701L, 5001L, 3L);

      assertThat(head.knowledgeBaseId()).isEqualTo(701L);
      assertThat(head.docId()).isEqualTo(5001L);
      assertThat(head.operationVersion()).isEqualTo(3L);
      assertThat(head.version()).isZero();
    }

    @Test
    @DisplayName("重复 docId → 返回已有 head，不覆盖 operationVersion")
    void duplicateDocIdReturnsExisting() {
      IngestionHead first = ingestionHeadDao.findOrCreate(702L, 5002L, 2L);
      IngestionHead second = ingestionHeadDao.findOrCreate(999L, 5002L, 9L);

      assertThat(second.docId()).isEqualTo(first.docId());
      assertThat(second.operationVersion()).isEqualTo(2L);
    }

    @Test
    @DisplayName("按 KB + docId 查询命中；跨 KB 不可见")
    void findByKnowledgeBaseIdAndDocIdScopedByKb() {
      ingestionHeadDao.findOrCreate(703L, 5003L, 1L);

      assertThat(ingestionHeadDao.findByKnowledgeBaseIdAndDocId(703L, 5003L)).isPresent();
      assertThat(ingestionHeadDao.findByKnowledgeBaseIdAndDocId(999L, 5003L)).isEmpty();
    }
  }

  @Nested
  @DisplayName("CAS 单调推进")
  class Advance {

    @Test
    @DisplayName("高版本 CAS 推进成功，版本递增")
    void higherVersionAdvances() {
      IngestionHead head = ingestionHeadDao.findOrCreate(711L, 5101L, 1L);

      int affected = ingestionHeadDao.advance(head, 2L);

      assertThat(affected).isEqualTo(1);
      IngestionHead refreshed = ingestionHeadDao.findByDocId(5101L).orElseThrow();
      assertThat(refreshed.operationVersion()).isEqualTo(2L);
      assertThat(refreshed.version()).isEqualTo(1L);
    }

    @Test
    @DisplayName("低版本 CAS 不推进，返回 0（幂等 ACK）")
    void lowerVersionIsNoOp() {
      IngestionHead head = ingestionHeadDao.findOrCreate(712L, 5102L, 5L);

      int affected = ingestionHeadDao.advance(head, 2L);

      assertThat(affected).isZero();
      assertThat(ingestionHeadDao.findByDocId(5102L).orElseThrow().operationVersion())
          .isEqualTo(5L);
    }

    @Test
    @DisplayName("等版本 CAS 不推进，返回 0（幂等 ACK）")
    void equalVersionIsNoOp() {
      IngestionHead head = ingestionHeadDao.findOrCreate(713L, 5103L, 4L);

      int affected = ingestionHeadDao.advance(head, 4L);

      assertThat(affected).isZero();
    }

    @Test
    @DisplayName("陈旧 version CAS 不推进，返回 0（并发抢占失败）")
    void staleVersionIsNoOp() {
      IngestionHead head = ingestionHeadDao.findOrCreate(714L, 5104L, 1L);
      ingestionHeadDao.advance(head, 5L); // head 推进，version 0 → 1

      // 用陈旧视图（version 仍为 0）尝试推进到 6
      IngestionHead staleView = new IngestionHead(714L, 5104L, 5L, 0L);
      int affected = ingestionHeadDao.advance(staleView, 6L);

      assertThat(affected).isZero();
    }
  }

  @Test
  @DisplayName("head 推进后旧活动 Job 被标记 SUPERSEDED（PENDING/PROCESSING）")
  void markSupersededAdvancesOldJobs() {
    long docId = 5201L;
    long kb = 721L;
    // 旧版本 v1 Job 处于 PROCESSING
    var job = ingestionJobDao.findOrCreate(721L, kb, docId, 1L, "TXT", 1L, "x");
    ingestionJobDao.markProcessing(job, java.time.LocalDateTime.now());

    int superseded = ingestionJobDao.markSuperseded(docId, 2L);

    assertThat(superseded).isEqualTo(1);
    var refreshed = ingestionJobDao.findByDocIdAndOperationVersion(docId, 1L).orElseThrow();
    assertThat(refreshed.getStatus())
        .isEqualTo(ai.cerbur.crag.storage.entity.IngestionJobStatus.SUPERSEDED);
  }

  @Test
  @DisplayName("READY Job 不被 SUPERSEDED 覆盖")
  void readyJobNotSuperseded() {
    long docId = 5202L;
    long kb = 722L;
    // head 必须存在且等于 operationVersion，否则 markReady 的 EXISTS 校验失败
    ingestionHeadDao.findOrCreate(kb, docId, 1L);
    var job = ingestionJobDao.findOrCreate(722L, kb, docId, 1L, "TXT", 1L, "x");
    ingestionJobDao.markProcessing(job, java.time.LocalDateTime.now());
    ingestionJobDao.markReady(job, java.time.LocalDateTime.now());

    int superseded = ingestionJobDao.markSuperseded(docId, 2L);

    assertThat(superseded).isZero();
    var refreshed = ingestionJobDao.findByDocIdAndOperationVersion(docId, 1L).orElseThrow();
    assertThat(refreshed.getStatus())
        .isEqualTo(ai.cerbur.crag.storage.entity.IngestionJobStatus.READY);
  }
}
