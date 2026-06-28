package ai.cerbur.crag.ingestion.cron;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.cerbur.crag.ingestion.job.IngestionJobService;
import ai.cerbur.crag.storage.ChunkDao;
import ai.cerbur.crag.storage.ChunkFtsDao;
import ai.cerbur.crag.storage.entity.Chunk;
import ai.cerbur.crag.storage.entity.ChunkStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

/**
 * SparseEmbeddingCron 单元测试（Plan 19）：验证 Sparse 写入从 chunk 投影派生 knowledgeBaseId，并在索引成功后尝试推进 Job 为
 * READY.
 */
@DisplayName("SparseEmbeddingCron 多 KB 写入与 READY 推进")
@ExtendWith(MockitoExtension.class)
class SparseEmbeddingCronTest {

  private static final long KB = 8484L;
  private static final long DOC = 9002L;

  @Mock private ChunkDao chunkDao;
  @Mock private ChunkFtsDao chunkFtsDao;
  @Mock private IngestionJobService ingestionJobService;

  @InjectMocks private SparseEmbeddingCron cron;

  @Test
  @DisplayName("成功索引 → fts 写入携带 chunk 的 knowledgeBaseId，并尝试推进 READY")
  void successPropagatesKbAndAdvancesReady() {
    Chunk child = Chunk.createChild(20L, KB, DOC, 1L, 1L, "内容", 5, 0, "{}");
    child.setVersion(0);
    lenient()
        .when(chunkDao.findSparseCandidates(any(), any(LocalDateTime.class), any(Pageable.class)))
        .thenReturn(List.of(child));
    when(chunkDao.tryMarkSparseProcessing(20L, ChunkStatus.INIT, 0)).thenReturn(1);
    when(chunkFtsDao.existsByChunkId(20L)).thenReturn(false);

    cron.processSparseEmbedding();

    verify(chunkFtsDao).insert(eq(20L), eq(KB), eq(1L), eq("内容"));
    verify(chunkDao)
        .updateSparseStatus(
            eq(20L), eq(ChunkStatus.SUCCESS), org.mockito.ArgumentMatchers.anyInt());
    verify(ingestionJobService).tryAdvanceReadyIfComplete(DOC);
  }
}
