package com.crag.demo.dao.repository;

import com.crag.demo.dao.entity.Chunk;
import com.crag.demo.dao.entity.ChunkStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Chunk Repository —— 基于 Spring Data JPA 的 chunk 表数据访问.
 *
 * 提供 Cron 扫表、CAS 抢占更新、按文档查询、按 parent 查询 children 等方法.
 * CAS 更新方法均返回 affected rows（0 = 被其他实例抢占，跳过）.
 *
 * @since 2026-06-10
 */
@Repository
public interface ChunkRepository extends JpaRepository<Chunk, String> {

    /**
     * Dense Cron 扫表 —— 找出所有待处理的 child chunk.
     *
     * 候选条件（OR）：
     * - dense_status = INIT 或 FAILED（正常候选）
     * - dense_status = PROCESSING 且 updated_at 早于超时阈值（超时回收）
     *
     * 仅返回 child chunk（parent_chunk_id != ''），排除 parent chunk.
     *
     * @param statuses         状态列表 [INIT, FAILED]
     * @param timeoutThreshold 超时阈值，PROCESSING 的 updated_at 早于此值的 chunk 也会被捞起
     * @param pageable         分页限制（每轮最多处理 N 条）
     * @return 候选 chunk 列表
     */
    @Query("SELECT c FROM Chunk c WHERE c.parentChunkId <> '' AND (c.denseStatus IN :statuses OR (c.denseStatus = com.crag.demo.dao.entity.ChunkStatus.PROCESSING AND c.updatedAt < :timeoutThreshold)) ORDER BY c.updatedAt ASC")
    List<Chunk> findDenseCandidates(@Param("statuses") List<ChunkStatus> statuses,
                                   @Param("timeoutThreshold") LocalDateTime timeoutThreshold,
                                   Pageable pageable);

    /**
     * CAS 抢占 —— 将 chunk 的 dense_status 从 expectedStatus 改为 PROCESSING.
     *
     * 仅当 chunk 当前状态 = expectedStatus 且版本未变时才更新，防止并发重复处理.
     * T1: expectedStatus = INIT（抢占未处理的 chunk）
     * T2: expectedStatus = FAILED（抢占失败的 chunk，自动重试）
     *
     * @param chunkId         chunk ID
     * @param expectedStatus  期望的当前状态（INIT 或 FAILED）
     * @param version         当前读取到的版本号，WHERE 条件中比对，防止丢失更新
     * @return affected rows（1 = 抢占成功，0 = 已被其他实例抢走或版本已变）
     */
    @Modifying
    @Transactional
    @Query("UPDATE Chunk c SET c.denseStatus = com.crag.demo.dao.entity.ChunkStatus.PROCESSING, c.updatedAt = CURRENT_TIMESTAMP, c.version = c.version + 1 WHERE c.chunkId = :chunkId AND c.denseStatus = :expectedStatus AND c.version = :version")
    int tryMarkProcessing(@Param("chunkId") String chunkId,
                          @Param("expectedStatus") ChunkStatus expectedStatus,
                          @Param("version") Integer version);

    /**
     * CAS 超时抢占 —— 将超时的 PROCESSING chunk 重新抢占.
     *
     * 仅当 chunk 当前状态 = PROCESSING、updated_at 早于超时阈值、且版本未变时才更新.
     * T3: 回收崩溃/卡住的 PROCESSING chunk.
     *
     * @param chunkId          chunk ID
     * @param timeoutThreshold 超时阈值，updated_at 必须早于此值
     * @param version          当前读取到的版本号，WHERE 条件中比对，防止丢失更新
     * @return affected rows（1 = 抢占成功，0 = 不超时或已被其他实例抢走）
     */
    @Modifying
    @Transactional
    @Query("UPDATE Chunk c SET c.denseStatus = com.crag.demo.dao.entity.ChunkStatus.PROCESSING, c.updatedAt = CURRENT_TIMESTAMP, c.version = c.version + 1 WHERE c.chunkId = :chunkId AND c.denseStatus = com.crag.demo.dao.entity.ChunkStatus.PROCESSING AND c.updatedAt < :timeoutThreshold AND c.version = :version")
    int tryMarkProcessingTimeout(@Param("chunkId") String chunkId,
                                 @Param("timeoutThreshold") LocalDateTime timeoutThreshold,
                                 @Param("version") Integer version);

    /**
     * 终态更新 —— 将 PROCESSING 的 chunk 改为 SUCCESS 或 FAILED.
     *
     * 仅当 chunk 当前状态 = PROCESSING 且版本未变时才更新，防止覆盖已被超时重抢的 chunk.
     * T4: newStatus = SUCCESS（embedding 成功）
     * T5: newStatus = FAILED（embedding 失败，下轮 Cron 通过 T2 重试）
     *
     * @param chunkId   chunk ID
     * @param newStatus 目标状态（SUCCESS 或 FAILED）
     * @param version   当前读取到的版本号，WHERE 条件中比对，防止丢失更新
     * @return affected rows（1 = 更新成功，0 = 状态已变更或版本已变）
     */
    @Modifying
    @Transactional
    @Query("UPDATE Chunk c SET c.denseStatus = :newStatus, c.updatedAt = CURRENT_TIMESTAMP, c.version = c.version + 1 WHERE c.chunkId = :chunkId AND c.denseStatus = com.crag.demo.dao.entity.ChunkStatus.PROCESSING AND c.version = :version")
    int updateDenseStatus(@Param("chunkId") String chunkId,
                          @Param("newStatus") ChunkStatus newStatus,
                          @Param("version") Integer version);

    /**
     * Sparse Cron 扫表 —— 找出所有待处理的 child chunk.
     *
     * 候选条件（OR）：
     * - sparse_status = INIT 或 FAILED（正常候选）
     * - sparse_status = PROCESSING 且 updated_at 早于超时阈值（超时回收）
     *
     * 仅返回 child chunk（parent_chunk_id != ''），排除 parent chunk.
     *
     * @param statuses         状态列表 [INIT, FAILED]
     * @param timeoutThreshold 超时阈值，PROCESSING 的 updated_at 早于此值的 chunk 也会被捞起
     * @param pageable         分页限制（每轮最多处理 N 条）
     * @return 候选 chunk 列表
     */
    @Query("SELECT c FROM Chunk c WHERE c.parentChunkId <> '' AND (c.sparseStatus IN :statuses OR (c.sparseStatus = com.crag.demo.dao.entity.ChunkStatus.PROCESSING AND c.updatedAt < :timeoutThreshold)) ORDER BY c.updatedAt ASC")
    List<Chunk> findSparseCandidates(@Param("statuses") List<ChunkStatus> statuses,
                                    @Param("timeoutThreshold") LocalDateTime timeoutThreshold,
                                    Pageable pageable);

    /**
     * CAS 抢占 —— 将 chunk 的 sparse_status 从 expectedStatus 改为 PROCESSING.
     *
     * 仅当 chunk 当前状态 = expectedStatus 且版本未变时才更新，防止并发重复处理.
     *
     * @param chunkId         chunk ID
     * @param expectedStatus  期望的当前状态（INIT 或 FAILED）
     * @param version         当前读取到的版本号，WHERE 条件中比对，防止丢失更新
     * @return affected rows（1 = 抢占成功，0 = 已被其他实例抢走或版本已变）
     */
    @Modifying
    @Transactional
    @Query("UPDATE Chunk c SET c.sparseStatus = com.crag.demo.dao.entity.ChunkStatus.PROCESSING, c.updatedAt = CURRENT_TIMESTAMP, c.version = c.version + 1 WHERE c.chunkId = :chunkId AND c.sparseStatus = :expectedStatus AND c.version = :version")
    int tryMarkSparseProcessing(@Param("chunkId") String chunkId,
                                @Param("expectedStatus") ChunkStatus expectedStatus,
                                @Param("version") Integer version);

    /**
     * CAS 超时抢占 —— 将超时的 PROCESSING chunk 重新抢占.
     *
     * 仅当 chunk 当前状态 = PROCESSING、updated_at 早于超时阈值、且版本未变时才更新.
     *
     * @param chunkId          chunk ID
     * @param timeoutThreshold 超时阈值，updated_at 必须早于此值
     * @param version          当前读取到的版本号，WHERE 条件中比对，防止丢失更新
     * @return affected rows（1 = 抢占成功，0 = 不超时或已被其他实例抢走）
     */
    @Modifying
    @Transactional
    @Query("UPDATE Chunk c SET c.sparseStatus = com.crag.demo.dao.entity.ChunkStatus.PROCESSING, c.updatedAt = CURRENT_TIMESTAMP, c.version = c.version + 1 WHERE c.chunkId = :chunkId AND c.sparseStatus = com.crag.demo.dao.entity.ChunkStatus.PROCESSING AND c.updatedAt < :timeoutThreshold AND c.version = :version")
    int tryMarkSparseProcessingTimeout(@Param("chunkId") String chunkId,
                                       @Param("timeoutThreshold") LocalDateTime timeoutThreshold,
                                       @Param("version") Integer version);

    /**
     * 终态更新 —— 将 PROCESSING 的 chunk 改为 SUCCESS 或 FAILED.
     *
     * 仅当 chunk 当前状态 = PROCESSING 且版本未变时才更新，防止覆盖已被超时重抢的 chunk.
     *
     * @param chunkId   chunk ID
     * @param newStatus 目标状态（SUCCESS 或 FAILED）
     * @param version   当前读取到的版本号，WHERE 条件中比对，防止丢失更新
     * @return affected rows（1 = 更新成功，0 = 状态已变更或版本已变）
     */
    @Modifying
    @Transactional
    @Query("UPDATE Chunk c SET c.sparseStatus = :newStatus, c.updatedAt = CURRENT_TIMESTAMP, c.version = c.version + 1 WHERE c.chunkId = :chunkId AND c.sparseStatus = com.crag.demo.dao.entity.ChunkStatus.PROCESSING AND c.version = :version")
    int updateSparseStatus(@Param("chunkId") String chunkId,
                           @Param("newStatus") ChunkStatus newStatus,
                           @Param("version") Integer version);

    /**
     * 按 sparse_status 查询 chunk.
     *
     * 历史兼容方法，保留给简单状态查询场景；Cron 优先使用 findSparseCandidates 做分页和超时回收.
     *
     * @param statuses 状态列表
     * @return 匹配的 chunk 列表
     */
    List<Chunk> findBySparseStatusIn(List<ChunkStatus> statuses);

    /**
     * 按文档 ID 查询所有 chunk（parent + child）.
     *
     * @param docId 文档 ID
     * @return 该文档下的所有 chunk
     */
    List<Chunk> findByDocId(String docId);

    /**
     * 按 parent chunk ID 查询其下所有 child chunk.
     *
     * @param parentChunkId parent chunk ID
     * @return 该 parent 下的所有 child chunk
     */
    List<Chunk> findByParentChunkId(String parentChunkId);
}
