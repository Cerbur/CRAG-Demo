package ai.cerbur.crag.ingestion.cron;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.cerbur.crag.ingestion.dense.DenseEmbeddingService;
import ai.cerbur.crag.ingestion.job.IngestionJobService;
import ai.cerbur.crag.storage.ChunkDao;
import ai.cerbur.crag.storage.ChunkEmbeddingDao;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * DenseEmbeddingCron 单元测试（Plan 19）：验证 Dense 写入从 chunk 投影派生 knowledgeBaseId，并在索引成功后尝试推进 Job 为 READY.
 */
@DisplayName("DenseEmbeddingCron 多 KB 写入与 READY 推进")
@ExtendWith(MockitoExtension.class)
class DenseEmbeddingCronTest {

  private static final long KB = 4242L;
  private static final long DOC = 9001L;

  @Mock private ChunkDao chunkDao;
  @Mock private ChunkEmbeddingDao chunkEmbeddingDao;
  @Mock private DenseEmbeddingService denseEmbeddingService;
  @Mock private IngestionJobService ingestionJobService;

  @InjectMocks private DenseEmbeddingCron cron;

  @Test
  @DisplayName("成功索引 → embedding 写入携带 chunk 的 knowledgeBaseId，并尝试推进 READY")
  void successPropagatesKbAndAdvancesReady() {
    Chunk child = Chunk.createChild(10L, KB, DOC, 1L, "content", 5, 0, "{}");
    child.setVersion(0);
    lenient()
        .when(chunkDao.findDenseCandidates(any(), any(LocalDateTime.class), any(Pageable.class)))
        .thenReturn(List.of(child));
    when(chunkDao.tryMarkProcessing(10L, ChunkStatus.INIT, 0)).thenReturn(1);
    when(chunkEmbeddingDao.existsByChunkId(10L)).thenReturn(false);
    when(denseEmbeddingService.embed("content")).thenReturn(new float[] {0.1f, 0.2f});

    cron.processDenseEmbedding();

    verify(chunkEmbeddingDao).insert(eq(10L), eq(KB), any(float[].class));
    verify(chunkDao).updateDenseStatus(eq(10L), eq(ChunkStatus.SUCCESS), anyInt());
    verify(ingestionJobService).tryAdvanceReadyIfComplete(DOC);
  }

  @Test
  @DisplayName("无候选 → 不调用 tryAdvanceReadyIfComplete")
  void noCandidatesDoesNotAdvance() {
    when(chunkDao.findDenseCandidates(any(), any(LocalDateTime.class), any(Pageable.class)))
        .thenReturn(List.of());

    cron.processDenseEmbedding();

    verify(ingestionJobService, org.mockito.Mockito.never()).tryAdvanceReadyIfComplete(anyLong());
  }

  @SuppressWarnings("unused")
  private Pageable unusedPageable() {
    return PageRequest.ofSize(1);
  }
}
