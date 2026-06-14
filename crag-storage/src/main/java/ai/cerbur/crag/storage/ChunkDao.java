package ai.cerbur.crag.storage;

import ai.cerbur.crag.storage.entity.Chunk;
import ai.cerbur.crag.storage.entity.ChunkStatus;
import ai.cerbur.crag.storage.repository.ChunkRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

/**
 * Chunk DAO —— chunk 表业务数据访问，只依赖 ChunkRepository.
 *
 * 一期方法直接透传 Repository，为 Cron/Service 层提供符合分层规范的合法入口。
 * 后续如需在扫表/CAS 更新前添加业务判断，在此层扩展。
 *
 * @since 2026-06-13
 */
@Component
public class ChunkDao {

    @Autowired
    private ChunkRepository chunkRepository;

    /**
     * Dense Cron 扫表 —— 找出所有待处理的 child chunk.
     *
     * @param statuses         状态列表 [INIT, FAILED]
     * @param timeoutThreshold 超时阈值
     * @param pageable         分页限制
     * @return 候选 chunk 列表
     */
    public List<Chunk> findDenseCandidates(List<ChunkStatus> statuses,
                                           LocalDateTime timeoutThreshold,
                                           Pageable pageable) {
        return chunkRepository.findDenseCandidates(statuses, timeoutThreshold, pageable);
    }

    /**
     * CAS 抢占 —— 将 chunk 从 expectedStatus 改为 PROCESSING.
     *
     * @param chunkId         chunk ID
     * @param expectedStatus  期望的当前状态
     * @param version         版本号
     * @return affected rows
     */
    public int tryMarkProcessing(String chunkId, ChunkStatus expectedStatus, Integer version) {
        return chunkRepository.tryMarkProcessing(chunkId, expectedStatus, version);
    }

    /**
     * CAS 超时抢占 —— 将超时的 PROCESSING chunk 重新抢占.
     *
     * @param chunkId          chunk ID
     * @param timeoutThreshold 超时阈值
     * @param version          版本号
     * @return affected rows
     */
    public int tryMarkProcessingTimeout(String chunkId, LocalDateTime timeoutThreshold, Integer version) {
        return chunkRepository.tryMarkProcessingTimeout(chunkId, timeoutThreshold, version);
    }

    /**
     * 终态更新 —— 将 PROCESSING chunk 改为 SUCCESS 或 FAILED.
     *
     * @param chunkId   chunk ID
     * @param newStatus 目标状态
     * @param version   版本号
     * @return affected rows
     */
    public int updateDenseStatus(String chunkId, ChunkStatus newStatus, Integer version) {
        return chunkRepository.updateDenseStatus(chunkId, newStatus, version);
    }

    /**
     * Sparse Cron 扫表 —— 找出所有待处理的 child chunk.
     *
     * @param statuses         状态列表 [INIT, FAILED]
     * @param timeoutThreshold 超时阈值
     * @param pageable         分页限制
     * @return 候选 chunk 列表
     */
    public List<Chunk> findSparseCandidates(List<ChunkStatus> statuses,
                                             LocalDateTime timeoutThreshold,
                                             Pageable pageable) {
        return chunkRepository.findSparseCandidates(statuses, timeoutThreshold, pageable);
    }

    /**
     * CAS 抢占 —— 将 chunk 的 sparse_status 从 expectedStatus 改为 PROCESSING.
     *
     * @param chunkId         chunk ID
     * @param expectedStatus  期望的当前状态
     * @param version         版本号
     * @return affected rows
     */
    public int tryMarkSparseProcessing(String chunkId, ChunkStatus expectedStatus, Integer version) {
        return chunkRepository.tryMarkSparseProcessing(chunkId, expectedStatus, version);
    }

    /**
     * CAS 超时抢占 —— 将超时的 PROCESSING chunk 重新抢占.
     *
     * @param chunkId          chunk ID
     * @param timeoutThreshold 超时阈值
     * @param version          版本号
     * @return affected rows
     */
    public int tryMarkSparseProcessingTimeout(String chunkId, LocalDateTime timeoutThreshold, Integer version) {
        return chunkRepository.tryMarkSparseProcessingTimeout(chunkId, timeoutThreshold, version);
    }

    /**
     * 终态更新 —— 将 PROCESSING chunk 改为 SUCCESS 或 FAILED.
     *
     * @param chunkId   chunk ID
     * @param newStatus 目标状态
     * @param version   版本号
     * @return affected rows
     */
    public int updateSparseStatus(String chunkId, ChunkStatus newStatus, Integer version) {
        return chunkRepository.updateSparseStatus(chunkId, newStatus, version);
    }

    /**
     * 批量写入 chunk.
     *
     * @param chunks chunk 实体列表
     * @return 持久化后的实体列表
     */
    public List<Chunk> saveAll(List<Chunk> chunks) {
        return chunkRepository.saveAll(chunks);
    }

    /**
     * chunk 表记录总数（供冒烟测试等场景用）.
     *
     * @return chunk 表记录数
     */
    public long count() {
        return chunkRepository.count();
    }
}
