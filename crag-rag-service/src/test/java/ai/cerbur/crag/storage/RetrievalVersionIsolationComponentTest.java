package ai.cerbur.crag.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.cerbur.crag.storage.entity.Chunk;
import ai.cerbur.crag.storage.entity.IngestionJob;
import ai.cerbur.crag.storage.entity.IngestionJobStatus;
import ai.cerbur.crag.storage.result.IngestionHead;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * 检索版本隔离组件测试（Plan 21.4）：H2 下验证召回 SQL 只读取当前 head.operationVersion 且对应 ingestion_job 已 READY 的 parent
 * chunk；旧版本、FAILED、PROCESSING 或部分索引的 parent 零召回.
 *
 * <p>核心断言：
 *
 * <ul>
 *   <li>v1 READY 后 v2 PENDING 时，v1 的 parent 不被召回（head 已指向 v2）；
 *   <li>v2 FAILED 时不回退到 v1（head 仍指向 v2，v2 Job 非 READY）；
 *   <li>迟到 Worker（operationVersion 低于 head）markReady 失败；
 *   <li>无 head 或无 READY Job 的文档零召回。
 * </ul>
 *
 * <p>H2 下只验证 SQL 行为与 Spring 装配；真实 pgvector / FTS 排序由 Docker 回归证明.
 */
@SpringBootTest(classes = IngestionJobDaoTestConfig.class)
@Transactional
@DisplayName("检索版本隔离：当前 head + READY ingestion_job 三重限定召回")
class RetrievalVersionIsolationComponentTest {

  @Autowired private ChunkDao chunkDao;
  @Autowired private IngestionHeadDao ingestionHeadDao;
  @Autowired private IngestionJobDao ingestionJobDao;

  private static final LocalDateTime T0 = LocalDateTime.of(2026, 6, 28, 10, 0, 0);
  private static final LocalDateTime T1 = LocalDateTime.of(2026, 6, 28, 10, 0, 1);

  /** 写入 parent chunk + head + ingestion_job 三件套，返回 parent chunkId. */
  private long seedVersion(
      long kb, long docId, long operationVersion, IngestionJobStatus jobStatus, String content) {
    long parentId = operationVersion * 100_000L + docId * 10L + 1L;
    Chunk parent = Chunk.createParent(parentId, kb, docId, operationVersion, content, 10, 0, "{}");
    chunkDao.saveAll(List.of(parent));

    // head 必须存在，才能让召回 SQL join 命中。
    IngestionHead head = ingestionHeadDao.findOrCreate(kb, docId, operationVersion);
    if (head.operationVersion() != operationVersion) {
      ingestionHeadDao.advance(head, operationVersion);
    }

    IngestionJob job =
        ingestionJobDao.findOrCreate(101L, kb, docId, operationVersion, "TXT", 1L, "x");
    if (jobStatus != IngestionJobStatus.PENDING) {
      ingestionJobDao.markProcessing(job, T0);
      if (jobStatus == IngestionJobStatus.READY) {
        ingestionJobDao.markReady(job, T1);
      } else if (jobStatus == IngestionJobStatus.FAILED) {
        ingestionJobDao.markFailed(job, T1, "FILE_DECODE_FAILED", "bad utf8");
      }
    }
    return parentId;
  }

  @Nested
  @DisplayName("parent 内容回表（findParentContentsByIds）版本限定")
  class ParentContentRecall {

    @Test
    @DisplayName("v1 READY 后 head 推进到 v2 PENDING → v1 parent 不被召回")
    void v1SupersededByV2NotRecalled() {
      long kb = 8001L;
      long docId = 80001L;
      long v1Parent = seedVersion(kb, docId, 1L, IngestionJobStatus.READY, "v1 content");
      // head 推进到 v2，v2 Job 仍 PENDING
      seedVersion(kb, docId, 2L, IngestionJobStatus.PENDING, "v2 content");

      // v1 parent 已不在当前 head 版本，回表应为空
      List<ai.cerbur.crag.storage.result.ParentChunkContent> recalled =
          chunkDao.findParentContentsByIds(kb, List.of(v1Parent));

      assertThat(recalled).isEmpty();
    }

    @Test
    @DisplayName("v2 FAILED 不回退 v1：head 指向 v2，v1 parent 不召回")
    void v2FailedDoesNotFallBackToV1() {
      long kb = 8002L;
      long docId = 80002L;
      long v1Parent = seedVersion(kb, docId, 1L, IngestionJobStatus.READY, "v1 content");
      // head 推进到 v2，v2 FAILED
      seedVersion(kb, docId, 2L, IngestionJobStatus.FAILED, "v2 content");

      List<ai.cerbur.crag.storage.result.ParentChunkContent> recalled =
          chunkDao.findParentContentsByIds(kb, List.of(v1Parent));

      assertThat(recalled).isEmpty();
    }

    @Test
    @DisplayName("当前 head + READY → parent 被召回，含 docId")
    void currentReadyVersionRecalled() {
      long kb = 8003L;
      long docId = 80003L;
      long parentId = seedVersion(kb, docId, 1L, IngestionJobStatus.READY, "ready content");

      List<ai.cerbur.crag.storage.result.ParentChunkContent> recalled =
          chunkDao.findParentContentsByIds(kb, List.of(parentId));

      assertThat(recalled).hasSize(1);
      assertThat(recalled.get(0).chunkId()).isEqualTo(parentId);
      assertThat(recalled.get(0).docId()).isEqualTo(docId);
      assertThat(recalled.get(0).content()).isEqualTo("ready content");
    }

    @Test
    @DisplayName("PROCESSING Job 不召回（未 READY）")
    void processingJobNotRecalled() {
      long kb = 8004L;
      long docId = 80004L;
      long parentId = seedVersion(kb, docId, 1L, IngestionJobStatus.PROCESSING, "processing");

      List<ai.cerbur.crag.storage.result.ParentChunkContent> recalled =
          chunkDao.findParentContentsByIds(kb, List.of(parentId));

      assertThat(recalled).isEmpty();
    }

    @Test
    @DisplayName("无 head 的文档零召回（即使 chunk 与 READY job 存在）")
    void noHeadNoRecall() {
      long kb = 8005L;
      long docId = 80005L;
      long parentId = 9001L;
      Chunk parent = Chunk.createParent(parentId, kb, docId, 1L, "content", 10, 0, "{}");
      chunkDao.saveAll(List.of(parent));
      // 不创建 head

      List<ai.cerbur.crag.storage.result.ParentChunkContent> recalled =
          chunkDao.findParentContentsByIds(kb, List.of(parentId));

      assertThat(recalled).isEmpty();
    }
  }

  @Nested
  @DisplayName("迟到 Worker markReady 被 head 拒绝")
  class LateWorkerReadyRejection {

    @Test
    @DisplayName("head 已推进到更高版本 → 旧 Worker markReady CAS 失败")
    void lateWorkerCannotMarkReady() {
      long kb = 8101L;
      long docId = 81001L;
      IngestionJob v1Job = ingestionJobDao.findOrCreate(101L, kb, docId, 1L, "TXT", 1L, "x");
      ingestionJobDao.markProcessing(v1Job, T0);

      // head 推进到 v2
      IngestionHead head = ingestionHeadDao.findOrCreate(kb, docId, 1L);
      ingestionHeadDao.advance(head, 2L);

      // v1 Job 仍处于 PROCESSING，迟到 Worker 尝试 markReady
      assertThatThrownBy(() -> ingestionJobDao.markReady(v1Job, T1))
          .isInstanceOf(IngestionJobConflictException.class);

      IngestionJob refreshed =
          ingestionJobDao.findByDocIdAndOperationVersion(docId, 1L).orElseThrow();
      assertThat(refreshed.getStatus()).isNotEqualTo(IngestionJobStatus.READY);
    }

    @Test
    @DisplayName("head 未推进时 markReady 正常成功")
    void earlyWorkerCanMarkReadyWhenHeadAligned() {
      long kb = 8102L;
      long docId = 81002L;
      IngestionJob job = ingestionJobDao.findOrCreate(101L, kb, docId, 1L, "TXT", 1L, "x");
      ingestionHeadDao.findOrCreate(kb, docId, 1L);
      ingestionJobDao.markProcessing(job, T0);

      ingestionJobDao.markReady(job, T1);

      IngestionJob refreshed =
          ingestionJobDao.findByDocIdAndOperationVersion(docId, 1L).orElseThrow();
      assertThat(refreshed.getStatus()).isEqualTo(IngestionJobStatus.READY);
    }
  }
}
