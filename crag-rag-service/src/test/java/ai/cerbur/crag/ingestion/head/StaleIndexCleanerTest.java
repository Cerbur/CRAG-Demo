package ai.cerbur.crag.ingestion.head;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.cerbur.crag.storage.ChunkDao;
import ai.cerbur.crag.storage.ChunkEmbeddingDao;
import ai.cerbur.crag.storage.ChunkFtsDao;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * StaleIndexCleaner 单元测试（plan_21/21.5）。
 *
 * <p>验证：
 *
 * <ul>
 *   <li>新版本开始前按 (docId, oldOpVersion) 批量清理 chunk / embedding / fts；
 *   <li>无残留时为 no-op。
 * </ul>
 */
@DisplayName("StaleIndexCleaner")
@ExtendWith(MockitoExtension.class)
class StaleIndexCleanerTest {

  @Mock private ChunkDao chunkDao;
  @Mock private ChunkEmbeddingDao chunkEmbeddingDao;
  @Mock private ChunkFtsDao chunkFtsDao;

  @InjectMocks private StaleIndexCleaner cleaner;

  @Test
  @DisplayName("清理旧版本残留：chunk / embedding / fts 全部按 docId+opVersion 删除")
  void cleansStaleResiduesBeforeNewVersion() {
    when(chunkDao.deleteByDocIdAndOperationVersion(3001L, 1L)).thenReturn(5);
    when(chunkEmbeddingDao.deleteByChunkIdsForDocAndVersion(3001L, 1L)).thenReturn(3);
    when(chunkFtsDao.deleteByChunkIdsForDocAndVersion(3001L, 1L)).thenReturn(3);

    int cleaned = cleaner.cleanBeforeNewVersion(3001L, 1L);

    assertThat(cleaned).isEqualTo(5);
    verify(chunkDao).deleteByDocIdAndOperationVersion(3001L, 1L);
    verify(chunkEmbeddingDao).deleteByChunkIdsForDocAndVersion(3001L, 1L);
    verify(chunkFtsDao).deleteByChunkIdsForDocAndVersion(3001L, 1L);
  }

  @Test
  @DisplayName("无残留时为 no-op")
  void noResidueIsNoOp() {
    when(chunkDao.deleteByDocIdAndOperationVersion(3002L, 1L)).thenReturn(0);
    when(chunkEmbeddingDao.deleteByChunkIdsForDocAndVersion(3002L, 1L)).thenReturn(0);
    when(chunkFtsDao.deleteByChunkIdsForDocAndVersion(3002L, 1L)).thenReturn(0);

    int cleaned = cleaner.cleanBeforeNewVersion(3002L, 1L);

    assertThat(cleaned).isZero();
  }
}
