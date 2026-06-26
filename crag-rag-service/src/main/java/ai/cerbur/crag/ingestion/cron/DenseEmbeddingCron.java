package ai.cerbur.crag.ingestion.cron;

import ai.cerbur.crag.ingestion.dense.DenseEmbeddingService;
import ai.cerbur.crag.ingestion.job.IngestionJobService;
import ai.cerbur.crag.retrieval.api.embedding.EmbeddingException;
import ai.cerbur.crag.storage.ChunkDao;
import ai.cerbur.crag.storage.ChunkEmbeddingDao;
import ai.cerbur.crag.storage.entity.Chunk;
import ai.cerbur.crag.storage.entity.ChunkStatus;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Dense Embedding 定时任务 —— Cron 触发扫表 + CAS 抢占 + 流程编排.
 *
 * <p>定时扫描 chunk 表中需要做 Dense Embedding 的 child chunk（INIT / FAILED / 超时 PROCESSING）， 通过 CAS
 * 抢占方式保证多实例并发安全，调用 DenseEmbeddingService 做核心向量化， 最后通过 ChunkEmbeddingDao 写入向量并更新 chunk 状态.
 *
 * <p>职责边界： - 本类负责定时触发、流程编排（只依赖 Dao，不直接依赖 Repository） - ChunkDao 负责 chunk 表读写 + CAS 抢占 -
 * DenseEmbeddingService 负责核心 Embedding 调用逻辑 - ChunkEmbeddingDao 负责 chunk_embedding 表读写 + pgvector
 * 类型转换 - 后续新增其他定时任务同样放入 cron 包
 *
 * @since 2026-06-13
 */
@Component
public class DenseEmbeddingCron {

  private static final Logger log = LoggerFactory.getLogger(DenseEmbeddingCron.class);

  /** 每轮最多处理的 chunk 数量. */
  private static final int BATCH_SIZE = 100;

  /** Processing 超时阈值：5 分钟. 超过此时间的 PROCESSING chunk 被视为卡住，会被 Cron 重新捞起处理. */
  private static final Duration PROCESSING_TIMEOUT = Duration.ofMinutes(5);

  /** 扫表候选状态：INIT（未处理）和 FAILED（失败待重试）. */
  private static final List<ChunkStatus> CANDIDATE_STATUSES =
      Arrays.asList(ChunkStatus.INIT, ChunkStatus.FAILED);

  /** Chunk 表 DAO —— 扫表 + CAS 抢占 + 状态更新. */
  @Autowired private ChunkDao chunkDao;

  /** Chunk Embedding 表 DAO —— 幂等检查 + 向量写入. */
  @Autowired private ChunkEmbeddingDao chunkEmbeddingDao;

  /** Dense Embedding 服务 —— 调用 Sidecar /embed 做核心向量化. */
  @Autowired private DenseEmbeddingService denseEmbeddingService;

  /** Ingestion Job 状态服务 —— Dense 索引完成后尝试推进对应文档 Job 为 READY（Plan 19）. */
  @Autowired private IngestionJobService ingestionJobService;

  /**
   * Dense Embedding 定时处理 —— 每 10 秒执行一次.
   *
   * <p>流程： 1. 扫表找出候选 chunk（INIT / FAILED / 超时 PROCESSING） 2. 逐个 CAS 抢占（T1: INIT→PROCESSING, T2:
   * FAILED→PROCESSING, T3: 超时 PROCESSING→PROCESSING） 3. 抢占成功后调 DenseEmbeddingService.embed() 做向量化
   * 4. 成功 → 幂等检查（chunk_embedding 是否已存在）→ 不存在则 INSERT → 标记 SUCCESS（T4） 5. 失败 → 标记 FAILED（T5），下轮自动重试
   */
  @Scheduled(cron = "*/10 * * * * *")
  void processDenseEmbedding() {
    // Step 1: 扫表 —— 找出所有候选 chunk
    LocalDateTime timeoutThreshold = LocalDateTime.now().minus(PROCESSING_TIMEOUT);
    List<Chunk> candidates =
        chunkDao.findDenseCandidates(
            CANDIDATE_STATUSES, timeoutThreshold, PageRequest.ofSize(BATCH_SIZE));

    if (candidates.isEmpty()) {
      log.debug("DenseEmbeddingCron: no candidates found");
      return;
    }

    log.info("DenseEmbeddingCron: found {} candidate(s) for dense embedding", candidates.size());

    int successCount = 0;
    int failedCount = 0;
    int skippedCount = 0;
    Set<Long> indexedDocIds = new LinkedHashSet<>();

    for (Chunk chunk : candidates) {
      // Step 2: 根据当前状态选择对应的 CAS 抢占 SQL，同时传入版本号做乐观锁校验
      int affected =
          switch (chunk.getDenseStatus()) {
            case INIT ->
                chunkDao.tryMarkProcessing(
                    chunk.getChunkId(), ChunkStatus.INIT, chunk.getVersion()); // T1
            case FAILED ->
                chunkDao.tryMarkProcessing(
                    chunk.getChunkId(), ChunkStatus.FAILED, chunk.getVersion()); // T2
            case PROCESSING ->
                chunkDao.tryMarkProcessingTimeout(
                    chunk.getChunkId(), timeoutThreshold, chunk.getVersion()); // T3
            default -> 0;
          };

      if (affected == 0) {
        // 被其他实例抢占或版本已变，跳过
        skippedCount++;
        continue;
      }

      // CAS 成功后 DB 端 version 已 +1，本地同步递增，保证后续 updateDenseStatus 的版本校验一致
      chunk.setVersion(chunk.getVersion() + 1);

      // Step 3: 执行 Embedding 向量化
      try {
        // Step 4: 幂等检查 —— 如果 embedding 已存在（上次写入成功但状态未更新），直接标记成功
        if (chunkEmbeddingDao.existsByChunkId(chunk.getChunkId())) {
          log.debug("Embedding already exists for chunk {}, marking SUCCESS", chunk.getChunkId());
          chunkDao.updateDenseStatus(chunk.getChunkId(), ChunkStatus.SUCCESS, chunk.getVersion());
          indexedDocIds.add(chunk.getDocId());
          successCount++;
          continue;
        }

        float[] vector = denseEmbeddingService.embed(chunk.getContent());

        // Step 4b: 写入 chunk_embedding（纯 INSERT，先查后插保证幂等）。
        // knowledge_base_id 从可信 chunk 投影派生，保证三表 KB 一致。
        chunkEmbeddingDao.insert(chunk.getChunkId(), chunk.getKnowledgeBaseId(), vector);
        chunkDao.updateDenseStatus(chunk.getChunkId(), ChunkStatus.SUCCESS, chunk.getVersion());
        indexedDocIds.add(chunk.getDocId());
        successCount++;
        log.debug("Dense embedding success — chunkId={}", chunk.getChunkId());
      } catch (EmbeddingException | DuplicateKeyException e) {
        // Step 5: 标记 FAILED（T5），下轮 Cron 通过 T2 自动重试
        // DuplicateKeyException：极端并发下另一实例已写入，下轮 existsByChunkId 命中直接标记成功
        // 也可能是 updateDenseStatus(SUCCESS) 的 CAS 版本冲突，同样标记 FAILED 等重试
        log.warn(
            "Dense embedding failed for chunk {}, will retry next round: {}",
            chunk.getChunkId(),
            e.getMessage());
        try {
          chunkDao.updateDenseStatus(chunk.getChunkId(), ChunkStatus.FAILED, chunk.getVersion());
          failedCount++;
        } catch (DuplicateKeyException dke) {
          // FAILED 更新也 CAS 冲突 → 另一实例已接管此 chunk，无需再标记
          log.debug(
              "CAS FAILED update also conflicted for chunk {}, another instance took over",
              chunk.getChunkId());
        }
      } catch (RuntimeException e) {
        // 未预期的异常（如 DB 类型映射错误），同样标记 FAILED 避免 chunk 卡在 PROCESSING
        log.error(
            "Unexpected error during dense embedding for chunk {}, marking FAILED",
            chunk.getChunkId(),
            e);
        try {
          chunkDao.updateDenseStatus(chunk.getChunkId(), ChunkStatus.FAILED, chunk.getVersion());
          failedCount++;
        } catch (DuplicateKeyException dke) {
          // FAILED 更新也 CAS 冲突 → 另一实例已接管此 chunk，无需再标记
          log.debug(
              "CAS FAILED update also conflicted for chunk {}, another instance took over",
              chunk.getChunkId());
        }
      }
    }

    // Dense 索引完成后，尝试推进对应文档的 ingestion_job 为 READY（当 Sparse 也完成时生效）。
    for (long docId : indexedDocIds) {
      ingestionJobService.tryAdvanceReadyIfComplete(docId);
    }

    log.info(
        "DenseEmbeddingCron round complete — success={}, failed={}, skipped={}",
        successCount,
        failedCount,
        skippedCount);
  }
}
