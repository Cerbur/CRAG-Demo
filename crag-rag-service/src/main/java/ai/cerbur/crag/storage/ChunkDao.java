package ai.cerbur.crag.storage;

import ai.cerbur.crag.storage.entity.Chunk;
import ai.cerbur.crag.storage.entity.ChunkStatus;
import ai.cerbur.crag.storage.repository.ChunkRepository;
import ai.cerbur.crag.storage.result.ParentChunkContent;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

/**
 * Chunk DAO —— chunk 表业务数据访问，只依赖 ChunkRepository.
 *
 * <p>一期方法直接透传 Repository，为 Cron/Service 层提供符合分层规范的合法入口。 后续如需在扫表/CAS 更新前添加业务判断，在此层扩展。
 *
 * @since 2026-06-13
 */
@Component
public class ChunkDao {

  @Autowired private ChunkRepository chunkRepository;

  /**
   * Dense Cron 扫表 —— 找出所有待处理的 child chunk.
   *
   * @param statuses 状态列表 [INIT, FAILED]
   * @param timeoutThreshold 超时阈值
   * @param pageable 分页限制
   * @return 候选 chunk 列表
   */
  public List<Chunk> findDenseCandidates(
      List<ChunkStatus> statuses, LocalDateTime timeoutThreshold, Pageable pageable) {
    return chunkRepository.findDenseCandidates(statuses, timeoutThreshold, pageable);
  }

  /**
   * CAS 抢占 —— 将 chunk 从 expectedStatus 改为 PROCESSING.
   *
   * @param chunkId chunk ID
   * @param expectedStatus 期望的当前状态
   * @param version 版本号
   * @return affected rows
   */
  public int tryMarkProcessing(long chunkId, ChunkStatus expectedStatus, Integer version) {
    return chunkRepository.tryMarkProcessing(chunkId, expectedStatus, version);
  }

  /**
   * CAS 超时抢占 —— 将超时的 PROCESSING chunk 重新抢占.
   *
   * @param chunkId chunk ID
   * @param timeoutThreshold 超时阈值
   * @param version 版本号
   * @return affected rows
   */
  public int tryMarkProcessingTimeout(
      long chunkId, LocalDateTime timeoutThreshold, Integer version) {
    return chunkRepository.tryMarkProcessingTimeout(chunkId, timeoutThreshold, version);
  }

  /**
   * 终态更新 —— 将 PROCESSING chunk 改为 SUCCESS 或 FAILED.
   *
   * <p>CAS 带 version 条件更新，若 affected == 0 表示版本已被其他实例变更， 此时抛出 DuplicateKeyException 由调用方统一处理.
   *
   * @param chunkId chunk ID
   * @param newStatus 目标状态
   * @param version 版本号
   * @return affected rows（始终 ≥ 1）
   * @throws DuplicateKeyException 当 affected == 0（版本冲突，另一实例已接管）
   */
  public int updateDenseStatus(long chunkId, ChunkStatus newStatus, Integer version) {
    int affected = chunkRepository.updateDenseStatus(chunkId, newStatus, version);
    if (affected == 0) {
      throw new DuplicateKeyException(
          "CAS updateDenseStatus failed: chunk "
              + chunkId
              + " version "
              + version
              + " already stale");
    }
    return affected;
  }

  /**
   * Sparse Cron 扫表 —— 找出所有待处理的 child chunk.
   *
   * @param statuses 状态列表 [INIT, FAILED]
   * @param timeoutThreshold 超时阈值
   * @param pageable 分页限制
   * @return 候选 chunk 列表
   */
  public List<Chunk> findSparseCandidates(
      List<ChunkStatus> statuses, LocalDateTime timeoutThreshold, Pageable pageable) {
    return chunkRepository.findSparseCandidates(statuses, timeoutThreshold, pageable);
  }

  /**
   * CAS 抢占 —— 将 chunk 的 sparse_status 从 expectedStatus 改为 PROCESSING.
   *
   * @param chunkId chunk ID
   * @param expectedStatus 期望的当前状态
   * @param version 版本号
   * @return affected rows
   */
  public int tryMarkSparseProcessing(long chunkId, ChunkStatus expectedStatus, Integer version) {
    return chunkRepository.tryMarkSparseProcessing(chunkId, expectedStatus, version);
  }

  /**
   * CAS 超时抢占 —— 将超时的 PROCESSING chunk 重新抢占.
   *
   * @param chunkId chunk ID
   * @param timeoutThreshold 超时阈值
   * @param version 版本号
   * @return affected rows
   */
  public int tryMarkSparseProcessingTimeout(
      long chunkId, LocalDateTime timeoutThreshold, Integer version) {
    return chunkRepository.tryMarkSparseProcessingTimeout(chunkId, timeoutThreshold, version);
  }

  /**
   * 终态更新 —— 将 PROCESSING chunk 改为 SUCCESS 或 FAILED.
   *
   * <p>CAS 带 version 条件更新，若 affected == 0 表示版本已被其他实例变更， 此时抛出 DuplicateKeyException 由调用方统一处理.
   *
   * @param chunkId chunk ID
   * @param newStatus 目标状态
   * @param version 版本号
   * @return affected rows（始终 ≥ 1）
   * @throws DuplicateKeyException 当 affected == 0（版本冲突，另一实例已接管）
   */
  public int updateSparseStatus(long chunkId, ChunkStatus newStatus, Integer version) {
    int affected = chunkRepository.updateSparseStatus(chunkId, newStatus, version);
    if (affected == 0) {
      throw new DuplicateKeyException(
          "CAS updateSparseStatus failed: chunk "
              + chunkId
              + " version "
              + version
              + " already stale");
    }
    return affected;
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

  /**
   * 按 chunk ID 查询单个 chunk（供检索回表等场景用）.
   *
   * @param chunkId chunk ID
   * @return chunk 实体，不存在时返回 null
   */
  public Chunk findByChunkId(long chunkId) {
    return chunkRepository.findById(chunkId).orElse(null);
  }

  /**
   * 按 chunk ID 列表批量查询 chunk（供检索回表等场景用）.
   *
   * @param chunkIds chunk ID 列表
   * @return chunk 实体列表，不存在的 ID 不会出现在结果中
   */
  public List<Chunk> findByChunkIds(List<Long> chunkIds) {
    return chunkRepository.findAllById(chunkIds);
  }

  /**
   * 按 parent chunk ID 查询其下所有 child chunk（供 rerank 邻接窗口扩展使用）.
   *
   * @param parentChunkId parent chunk ID
   * @return 该 parent 下的 child chunk 列表
   */
  public List<Chunk> findByParentChunkId(long parentChunkId) {
    return chunkRepository.findByParentChunkId(parentChunkId);
  }

  /**
   * 按 parent chunk ID 与 child index 批量查询 child chunk（供 rerank 邻接窗口扩展使用）.
   *
   * @param parentChunkIds parent chunk ID 列表
   * @param chunkIndexes child chunk index 列表
   * @return 命中 parent/index 集合的 child chunk 列表
   */
  public List<Chunk> findByParentChunkIdsAndChunkIndexes(
      List<Long> parentChunkIds, List<Integer> chunkIndexes) {
    return chunkRepository.findByParentChunkIdInAndChunkIndexIn(parentChunkIds, chunkIndexes);
  }

  /**
   * 按 chunk ID 列表批量查询 parent chunk 内容投影.
   *
   * <p>仅返回 {@code chunkId} 和 {@code content}，限定 parent 行（parent_chunk_id = 0）， 用于 Evidence 回表组装.
   * 不做顺序保证，调用方自行按 chunkId 建立映射.
   *
   * @param chunkIds chunk ID 列表
   * @return parent chunk 内容投影列表，不存在的 ID 不会出现在结果中
   */
  public List<ParentChunkContent> findParentContentsByIds(List<Long> chunkIds) {
    if (chunkIds == null || chunkIds.isEmpty()) {
      return Collections.emptyList();
    }
    return chunkRepository.findParentContentsByIds(chunkIds);
  }

  /**
   * 统计指定文档下尚未完全索引（Dense 或 Sparse 仍非终态）的 chunk 数量（Plan 19）.
   *
   * <p>0 表示该文档所有 child chunk 已完成 Dense+Sparse 索引，可用于推进 ingestion_job 为 READY.
   *
   * @param docId 文档 ID
   * @return 尚未完全索引的 chunk 数量
   */
  public long countByDocIdNotFullyIndexed(long docId) {
    return chunkRepository.countByDocIdNotFullyIndexed(
        docId, List.of(ChunkStatus.SUCCESS, ChunkStatus.SKIPPED));
  }
}
