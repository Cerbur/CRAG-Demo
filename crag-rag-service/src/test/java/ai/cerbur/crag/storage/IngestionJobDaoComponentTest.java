package ai.cerbur.crag.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.cerbur.crag.storage.entity.IngestionJob;
import ai.cerbur.crag.storage.entity.IngestionJobStatus;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * IngestionJobDao 轻量组件测试（Plan 19）：H2 下验证幂等创建、业务键查询、CAS 状态推进 PENDING → PROCESSING → READY /
 * FAILED，以及 重复业务键不创建第二个 Job.
 *
 * <p>H2 仅证明 DAO 行为与 Spring 装配，不表述为 PostgreSQL 方言或端到端兼容证明.
 */
@SpringBootTest(classes = IngestionJobDaoTestConfig.class)
@Transactional
@DisplayName("IngestionJobDao 幂等创建与 CAS 状态推进")
class IngestionJobDaoComponentTest {

  @Autowired private IngestionJobDao ingestionJobDao;

  private static final LocalDateTime T0 = LocalDateTime.of(2026, 6, 27, 10, 0, 0);
  private static final LocalDateTime T1 = LocalDateTime.of(2026, 6, 27, 10, 0, 1);

  @Nested
  @DisplayName("幂等创建与业务键查询")
  class FindOrCreate {

    @Test
    @DisplayName("首次创建 → 生成 ID、PENDING、版本 0、审计字段已设置")
    void createsPendingJobWithGeneratedId() {
      IngestionJob job = ingestionJobDao.findOrCreate(101L, 200L, 1001L, 1L, "TXT", 42L, "abc123");

      assertThat(job.getJobId()).isNotNull();
      assertThat(job.getStatus()).isEqualTo(IngestionJobStatus.PENDING);
      assertThat(job.getVersion()).isZero();
      assertThat(job.getTenantId()).isEqualTo(101L);
      assertThat(job.getKnowledgeBaseId()).isEqualTo(200L);
      assertThat(job.getDocId()).isEqualTo(1001L);
      assertThat(job.getOperationVersion()).isEqualTo(1L);
      assertThat(job.getFileType()).isEqualTo("TXT");
      assertThat(job.getSizeBytes()).isEqualTo(42L);
      assertThat(job.getSha256()).isEqualTo("abc123");
      assertThat(job.getStartedAt()).isNull();
      assertThat(job.getCompletedAt()).isNull();
    }

    @Test
    @DisplayName("重复业务键 → 返回已有 Job，不创建第二个，不覆盖事实")
    void duplicateBusinessKeyReturnsExisting() {
      IngestionJob first = ingestionJobDao.findOrCreate(101L, 200L, 1002L, 1L, "TXT", 10L, "a");
      IngestionJob second = ingestionJobDao.findOrCreate(999L, 888L, 1002L, 1L, "MD", 99L, "z");

      assertThat(second.getJobId()).isEqualTo(first.getJobId());
      assertThat(second.getStatus()).isEqualTo(IngestionJobStatus.PENDING);
      // 已有 Job 的事实不被重复事件覆盖。
      assertThat(second.getTenantId()).isEqualTo(101L);
      assertThat(second.getSha256()).isEqualTo("a");

      Optional<IngestionJob> lookup = ingestionJobDao.findByDocIdAndOperationVersion(1002L, 1L);
      assertThat(lookup).isPresent();
      assertThat(lookup.get().getJobId()).isEqualTo(first.getJobId());
    }

    @Test
    @DisplayName("按 KB + docId 查询命中；跨 KB 不可见")
    void findByKnowledgeBaseIdAndDocIdHits() {
      ingestionJobDao.findOrCreate(101L, 200L, 1003L, 1L, "TXT", 1L, "x");

      Optional<IngestionJob> hit = ingestionJobDao.findByKnowledgeBaseIdAndDocId(200L, 1003L);
      Optional<IngestionJob> miss = ingestionJobDao.findByKnowledgeBaseIdAndDocId(201L, 1003L);

      assertThat(hit).isPresent();
      assertThat(miss).as("跨 KB 查询不可见").isEmpty();
    }
  }

  @Nested
  @DisplayName("CAS 状态推进")
  class CasTransitions {

    @Test
    @DisplayName("PENDING → PROCESSING → READY，版本与时间戳推进")
    void pendingToProcessingToReady() {
      IngestionJob job = ingestionJobDao.findOrCreate(101L, 200L, 2001L, 1L, "TXT", 1L, "x");

      ingestionJobDao.markProcessing(job, T0);
      assertThat(job.getStatus()).isEqualTo(IngestionJobStatus.PROCESSING);
      assertThat(job.getVersion()).isEqualTo(1);
      assertThat(job.getStartedAt()).isEqualTo(T0);

      ingestionJobDao.markReady(job, T1);
      assertThat(job.getStatus()).isEqualTo(IngestionJobStatus.READY);
      assertThat(job.getVersion()).isEqualTo(2);
      assertThat(job.getCompletedAt()).isEqualTo(T1);
    }

    @Test
    @DisplayName("PROCESSING → FAILED，写入失败分类与安全摘要")
    void processingToFailedRecordsCategoryAndMessage() {
      IngestionJob job = ingestionJobDao.findOrCreate(101L, 200L, 2002L, 1L, "TXT", 1L, "x");
      ingestionJobDao.markProcessing(job, T0);

      ingestionJobDao.markFailed(job, T1, "FILE_DECODE_FAILED", "bad utf8");

      assertThat(job.getStatus()).isEqualTo(IngestionJobStatus.FAILED);
      assertThat(job.getFailureCategory()).isEqualTo("FILE_DECODE_FAILED");
      assertThat(job.getFailureMessage()).isEqualTo("bad utf8");
      assertThat(job.getCompletedAt()).isEqualTo(T1);
    }

    @Test
    @DisplayName("markProcessing 用陈旧版本 → 抛出 IngestionJobConflictException")
    void markProcessingWithStaleVersionThrowsConflict() {
      IngestionJob job = ingestionJobDao.findOrCreate(101L, 200L, 2003L, 1L, "TXT", 1L, "x");
      ingestionJobDao.markProcessing(job, T0); // 真实推进，DB 版本 0 → 1

      // 用未受持久化上下文管理的「陈旧视图」重试（版本 0），模拟另一实例已推进。
      // 直接改受管实体会触发 JPA 自动 flush，因此构造 transient 视图。
      IngestionJob staleView = IngestionJob.createPending(101L, 200L, 2003L, 1L, "TXT", 1L, "x");
      staleView.setVersion(0);

      assertThatThrownBy(() -> ingestionJobDao.markProcessing(staleView, T0))
          .isInstanceOf(IngestionJobConflictException.class);
    }

    @Test
    @DisplayName("markReady 在非 PROCESSING（仍 PENDING）→ 抛出冲突")
    void markReadyOnNonProcessingThrowsConflict() {
      IngestionJob job = ingestionJobDao.findOrCreate(101L, 200L, 2004L, 1L, "TXT", 1L, "x");
      // 未先 markProcessing，状态仍 PENDING，markReady 的 WHERE 要求 PROCESSING → affected 0。

      assertThatThrownBy(() -> ingestionJobDao.markReady(job, T1))
          .isInstanceOf(IngestionJobConflictException.class);
    }
  }

  @Test
  @DisplayName("countByKnowledgeBaseIdAndStatus 按 KB 与状态统计")
  void countByKnowledgeBaseIdAndStatus() {
    ingestionJobDao.findOrCreate(101L, 300L, 3001L, 1L, "TXT", 1L, "x");
    IngestionJob other = ingestionJobDao.findOrCreate(101L, 300L, 3002L, 1L, "TXT", 1L, "y");
    ingestionJobDao.markProcessing(other, T0);

    assertThat(ingestionJobDao.countByKnowledgeBaseIdAndStatus(300L, IngestionJobStatus.PENDING))
        .isEqualTo(1);
    assertThat(ingestionJobDao.countByKnowledgeBaseIdAndStatus(300L, IngestionJobStatus.PROCESSING))
        .isEqualTo(1);
    assertThat(ingestionJobDao.countByKnowledgeBaseIdAndStatus(999L, IngestionJobStatus.PENDING))
        .isZero();
  }
}
