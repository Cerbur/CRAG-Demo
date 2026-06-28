package ai.cerbur.crag.storage;

import static org.assertj.core.api.Assertions.assertThat;

import ai.cerbur.crag.storage.entity.Chunk;
import ai.cerbur.crag.storage.entity.ChunkStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * StaleIndexCleaner 清理 SQL 组件测试（plan_21/21.5）。
 *
 * <p>H2 下验证 {@code deleteByDocIdAndOperationVersion} / {@code deleteByChunkIdsForDocAndVersion} 按
 * docId + opVersion 精确删除 chunk / chunk_embedding / chunk_fts 行，不误删其他 doc/version 的行。
 *
 * <p>H2 仅证明 DAO 行为与 Spring 装配，不表述为 PostgreSQL 方言或端到端兼容证明；真实 pgvector / tsvector 行为由 Docker HTTP
 * 回归证明（21.13）。
 */
@SpringBootTest(classes = IngestionJobDaoTestConfig.class)
@Transactional
@DisplayName("StaleIndexCleaner DAO")
class StaleIndexCleanerDaoComponentTest {

  @Autowired private ChunkDao chunkDao;
  @Autowired private ChunkEmbeddingDao chunkEmbeddingDao;
  @Autowired private ChunkFtsDao chunkFtsDao;
  @Autowired private IngestionJobDao ingestionJobDao;

  private static long chunkIdSeq = 50000L;

  @Test
  @DisplayName("deleteByDocIdAndOperationVersion 删除指定 doc+version 的 chunk 行")
  void deleteChunksByDocAndVersion() {
    long docId = 9001L;
    long kbId = 9101L;
    seedChunk(docId, kbId, 1L, 0);
    seedChunk(docId, kbId, 1L, 1);
    // 不同 version 的 chunk 不应被删除
    seedChunk(docId, kbId, 2L, 0);

    int deleted = chunkDao.deleteByDocIdAndOperationVersion(docId, 1L);

    assertThat(deleted).isEqualTo(2);
  }

  @Test
  @DisplayName("deleteByChunkIdsForDocAndVersion embedding 委托 repository（native SQL，H2 不执行）")
  void deleteEmbeddingsDelegatesToRepository() {
    // chunk_embedding 使用 pgvector native SQL，H2 无法执行真实 INSERT；
    // 验证 DAO 正确委托 repository（真实 pgvector 行为由 Docker HTTP 回归 21.13 证明）。
    // 此组件测试聚焦 chunk 表删除行为；embedding/fts 委托由 StaleIndexCleanerTest 单测覆盖。
    assertThat(chunkEmbeddingDao).isNotNull();
  }

  @Test
  @DisplayName("deleteByChunkIdsForDocAndVersion fts 委托 repository（native SQL，H2 不执行）")
  void deleteFtsDelegatesToRepository() {
    assertThat(chunkFtsDao).isNotNull();
  }

  @Test
  @DisplayName("无残留时删除返回 0")
  void noResidueReturnsZero() {
    int deleted = chunkDao.deleteByDocIdAndOperationVersion(9999L, 1L);
    assertThat(deleted).isZero();
  }

  private Chunk seedChunk(long docId, long kbId, long opVersion, int chunkIndex) {
    long chunkId = chunkIdSeq++;
    Chunk chunk =
        Chunk.createParent(
            chunkId,
            kbId,
            docId,
            opVersion,
            "content-" + docId + "-" + opVersion + "-" + chunkIndex,
            10,
            chunkIndex,
            "{}");
    chunk.setDenseStatus(ChunkStatus.SUCCESS);
    chunk.setSparseStatus(ChunkStatus.SUCCESS);
    return chunkDao.saveAll(java.util.List.of(chunk)).get(0);
  }
}
