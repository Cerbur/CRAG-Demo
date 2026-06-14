package ai.cerbur.crag.ingestion.cron;

import ai.cerbur.crag.storage.ChunkDao;
import ai.cerbur.crag.storage.ChunkFtsDao;
import ai.cerbur.crag.storage.entity.Chunk;
import ai.cerbur.crag.storage.entity.ChunkStatus;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sparse Embedding 定时任务 —— Cron 触发扫表 + CAS 抢占 + FTS 写入.
 *
 * 定时扫描 chunk 表中需要做 Sparse FTS 分词的 child chunk（INIT / FAILED / 超时 PROCESSING），
 * 通过 CAS 抢占方式保证多实例并发安全，直接调用 ChunkFtsDao 写入 tsvector 全文检索记录.
 *
 * 与 DenseEmbeddingCron 的核心差异：
 * - Sparse 不调外部 HTTP 服务，直接在 DB 侧完成 CJK 分词 + tsvector 转换
 * - 无 EmbeddingException，异常来源只有 DB 写入冲突
 *
 * 职责边界：
 * - 本类负责定时触发、流程编排（只依赖 Dao，不直接依赖 Repository）
 * - ChunkDao 负责 chunk 表读写 + CAS 抢占
 * - ChunkFtsDao 负责 chunk_fts 表读写 + 幂等检查
 *
 * @since 2026-06-14
 */
@Component
public class SparseEmbeddingCron {

    private static final Logger log = LoggerFactory.getLogger(SparseEmbeddingCron.class);

    /**
     * 每轮最多处理的 chunk 数量.
     */
    private static final int BATCH_SIZE = 100;

    /**
     * Processing 超时阈值：5 分钟.
     * 超过此时间的 PROCESSING chunk 被视为卡住，会被 Cron 重新捞起处理.
     */
    private static final Duration PROCESSING_TIMEOUT = Duration.ofMinutes(5);

    /**
     * 扫表候选状态：INIT（未处理）和 FAILED（失败待重试）.
     */
    private static final List<ChunkStatus> CANDIDATE_STATUSES = Arrays.asList(ChunkStatus.INIT, ChunkStatus.FAILED);

    /**
     * Chunk 表 DAO —— 扫表 + CAS 抢占 + 状态更新.
     */
    @Autowired
    private ChunkDao chunkDao;

    /**
     * Chunk FTS 表 DAO —— 幂等检查 + FTS 写入.
     */
    @Autowired
    private ChunkFtsDao chunkFtsDao;

    /**
     * Sparse Embedding 定时处理 —— 每 10 秒执行一次.
     *
     * 流程：
     * 1. 扫表找出候选 chunk（INIT / FAILED / 超时 PROCESSING）
     * 2. 逐个 CAS 抢占（T1: INIT→PROCESSING, T2: FAILED→PROCESSING, T3: 超时 PROCESSING→PROCESSING）
     * 3. 抢占成功后先做幂等检查，FTS 已存在则直接标记 SUCCESS
     * 4. 写入 FTS（chunkFtsDao.insert，内部含 CJK 分词 + tsvector 转换）
     * 5. 成功 → 标记 SUCCESS（T4）
     * 6. 失败 → 标记 FAILED（T5），下轮自动重试
     */
    @Scheduled(cron = "*/10 * * * * *")
    void processSparseEmbedding() {
        // Step 1: 扫表 —— 找出所有候选 chunk
        LocalDateTime timeoutThreshold = LocalDateTime.now().minus(PROCESSING_TIMEOUT);
        List<Chunk> candidates = chunkDao.findSparseCandidates(
            CANDIDATE_STATUSES, timeoutThreshold, PageRequest.ofSize(BATCH_SIZE));

        if (candidates.isEmpty()) {
            log.debug("SparseEmbeddingCron: no candidates found");
            return;
        }

        log.info("SparseEmbeddingCron: found {} candidate(s) for sparse FTS", candidates.size());

        int successCount = 0;
        int failedCount = 0;
        int skippedCount = 0;

        for (Chunk chunk : candidates) {
            // Step 2: 根据当前状态选择对应的 CAS 抢占 SQL，同时传入版本号做乐观锁校验
            int affected = switch (chunk.getSparseStatus()) {
                case INIT -> chunkDao.tryMarkSparseProcessing(chunk.getChunkId(), ChunkStatus.INIT,
                    chunk.getVersion());                                                                      // T1
                case FAILED -> chunkDao.tryMarkSparseProcessing(chunk.getChunkId(), ChunkStatus.FAILED,
                    chunk.getVersion());                                                                      // T2
                case PROCESSING -> chunkDao.tryMarkSparseProcessingTimeout(chunk.getChunkId(),
                    timeoutThreshold, chunk.getVersion());                                                     // T3
                default -> 0;
            };

            if (affected == 0) {
                // 被其他实例抢占或版本已变，跳过
                skippedCount++;
                continue;
            }

            // CAS 成功后 DB 端 version 已 +1，本地同步递增，保证后续 updateSparseStatus 的版本校验一致
            chunk.setVersion(chunk.getVersion() + 1);

            // Step 3: 执行 FTS 写入
            try {
                // Step 4: 幂等检查 —— 如果 FTS 已存在（上次写入成功但状态未更新），直接标记成功
                if (chunkFtsDao.existsByChunkId(chunk.getChunkId())) {
                    log.debug("FTS already exists for chunk {}, marking SUCCESS", chunk.getChunkId());
                    chunkDao.updateSparseStatus(chunk.getChunkId(), ChunkStatus.SUCCESS, chunk.getVersion());
                    successCount++;
                    continue;
                }

                // Step 4b: 写入 chunk_fts（纯 INSERT，CJK 分词在 DB 侧完成）
                chunkFtsDao.insert(chunk.getChunkId(), chunk.getContent());
                chunkDao.updateSparseStatus(chunk.getChunkId(), ChunkStatus.SUCCESS, chunk.getVersion());
                successCount++;
                log.debug("Sparse FTS success — chunkId={}", chunk.getChunkId());
            } catch (DuplicateKeyException e) {
                // 极端并发下另一实例已写入，下轮 existsByChunkId 命中直接标记成功
                log.warn("Sparse FTS duplicate for chunk {}, will retry next round: {}",
                    chunk.getChunkId(), e.getMessage());
                chunkDao.updateSparseStatus(chunk.getChunkId(), ChunkStatus.FAILED, chunk.getVersion());
                failedCount++;
            } catch (RuntimeException e) {
                // 未预期的异常（如 DB 类型映射错误），同样标记 FAILED 避免 chunk 卡在 PROCESSING
                log.error("Unexpected error during sparse FTS for chunk {}, marking FAILED",
                    chunk.getChunkId(), e);
                chunkDao.updateSparseStatus(chunk.getChunkId(), ChunkStatus.FAILED, chunk.getVersion());
                failedCount++;
            }
        }

        log.info("SparseEmbeddingCron round complete — success={}, failed={}, skipped={}",
            successCount, failedCount, skippedCount);
    }
}
