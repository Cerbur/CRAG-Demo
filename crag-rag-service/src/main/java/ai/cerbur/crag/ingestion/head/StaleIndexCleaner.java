package ai.cerbur.crag.ingestion.head;

import ai.cerbur.crag.storage.ChunkDao;
import ai.cerbur.crag.storage.ChunkEmbeddingDao;
import ai.cerbur.crag.storage.ChunkFtsDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 旧失败索引残留清理器（plan_21/21.5）。
 *
 * <p>在 head advance 推进到新 operationVersion 后、新版本处理开始前，按 {@code (docId, oldOperationVersion)} 批量删除
 * chunk / chunk_embedding / chunk_fts 残留行，确保旧版本 FAILED/SUPERSEDED 的部分索引不会进入召回。
 *
 * <p>设计事实来源 §8.2：新版本处理前清理旧失败残留；失败或部分索引从未成为可查询数据。
 *
 * <p>清理只针对旧版本（{@code oldOperationVersion < newOperationVersion}）；旧 head 不执行清理。
 */
@Component
public class StaleIndexCleaner {

  private static final Logger log = LoggerFactory.getLogger(StaleIndexCleaner.class);

  @Autowired private ChunkDao chunkDao;
  @Autowired private ChunkEmbeddingDao chunkEmbeddingDao;
  @Autowired private ChunkFtsDao chunkFtsDao;

  /**
   * 清理指定 doc + 旧 operationVersion 的全部索引残留，返回被删除的 chunk 行数（近似清理规模）。
   *
   * <p>删除顺序：先 chunk_embedding / chunk_fts（依赖 chunk_id），再 chunk 行；同一事务内完成。
   *
   * @param docId 文档 ID
   * @param oldOperationVersion 旧 operationVersion
   * @return 被删除的 chunk 行数
   */
  @Transactional
  public int cleanBeforeNewVersion(long docId, long oldOperationVersion) {
    int embeddings = chunkEmbeddingDao.deleteByChunkIdsForDocAndVersion(docId, oldOperationVersion);
    int fts = chunkFtsDao.deleteByChunkIdsForDocAndVersion(docId, oldOperationVersion);
    int chunks = chunkDao.deleteByDocIdAndOperationVersion(docId, oldOperationVersion);
    if (chunks > 0 || embeddings > 0 || fts > 0) {
      log.info(
          "Stale index residues cleaned — docId={} opVersion={} chunks={} embeddings={} fts={}",
          docId,
          oldOperationVersion,
          chunks,
          embeddings,
          fts);
    }
    return chunks;
  }
}
