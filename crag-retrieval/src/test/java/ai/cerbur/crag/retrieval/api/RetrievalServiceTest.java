package ai.cerbur.crag.retrieval.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import ai.cerbur.crag.retrieval.api.RetrievalService.RerankCandidateSet;
import ai.cerbur.crag.retrieval.api.embedding.EmbeddingClient;
import ai.cerbur.crag.retrieval.api.result.ChunkSearchResult;
import ai.cerbur.crag.retrieval.api.result.ParentEvidenceResult;
import ai.cerbur.crag.retrieval.dense.DenseQueryService;
import ai.cerbur.crag.retrieval.rerank.RerankService;
import ai.cerbur.crag.retrieval.result.DenseSearchResult;
import ai.cerbur.crag.retrieval.result.RrfFusionResult;
import ai.cerbur.crag.retrieval.result.SparseSearchResult;
import ai.cerbur.crag.retrieval.rrf.RrfFusionService;
import ai.cerbur.crag.retrieval.sparse.SparseQueryService;
import ai.cerbur.crag.storage.ChunkDao;
import ai.cerbur.crag.storage.entity.Chunk;
import ai.cerbur.crag.storage.result.ParentChunkContent;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * RetrievalService 单元测试 —— 验证检索门面内 RRF 与 Rerank 候选扩展编排.
 *
 * <p>下游服务通过 Mockito 隔离，聚焦 child 维度 RRF、相邻 child 扩展和最终 topN 截断行为.
 *
 * @since 2026-06-17
 */
@DisplayName("RetrievalService 检索编排服务")
@ExtendWith(MockitoExtension.class)
class RetrievalServiceTest {

  @Mock private EmbeddingClient embeddingClient;

  @Mock private SparseQueryService sparseQueryService;

  @Mock private DenseQueryService denseQueryService;

  @Mock private RrfFusionService rrfFusionService;

  @Mock private RerankService rerankService;

  @Mock private ChunkDao chunkDao;

  @InjectMocks private RetrievalService retrievalService;

  @Test
  @SuppressWarnings("unchecked")
  @DisplayName("RRF 保持 child 维度，Rerank 使用 top child 及相邻 child")
  void rrfStaysAtChildLevelAndRerankUsesAdjacentChildren() {
    float[] embedding = new float[] {0.1f, 0.2f};
    Chunk child0 = child(1000L, 100L, 0, "前一个 child");
    Chunk child1 = child(1001L, 100L, 1, "命中的 child");
    Chunk child2 = child(1002L, 100L, 2, "后一个 child");
    ArgumentCaptor<List<RrfFusionResult>> candidatesCaptor = ArgumentCaptor.forClass(List.class);

    when(embeddingClient.embed("问题")).thenReturn(embedding);
    when(sparseQueryService.search("问题", 3))
        .thenReturn(List.of(new SparseSearchResult(1001L, 100L, 1, 0.9, "命中的 child")));
    when(denseQueryService.search(embedding, 3))
        .thenReturn(List.of(new DenseSearchResult(1001L, 100L, 1, 0.8, "命中的 child")));
    when(rrfFusionService.fuse(any(), any(), eq(1)))
        .thenReturn(List.of(new RrfFusionResult(1001L, 100L, 1, 2.0 / 61.0, "命中的 child", 0.9, 0.8)));
    when(chunkDao.findByParentChunkIdsAndChunkIndexes(List.of(100L), List.of(0, 2)))
        .thenReturn(List.of(child0, child2));
    when(rerankService.rerank(eq("问题"), candidatesCaptor.capture()))
        .thenReturn(
            List.of(
                finalResult(1002L, "后一个 child", 0.91),
                finalResult(1001L, "命中的 child", 0.80),
                finalResult(1000L, "前一个 child", 0.70)));

    List<ChunkSearchResult> results = retrievalService.retrieve("问题", 1);

    assertThat(candidatesCaptor.getValue())
        .extracting(RrfFusionResult::getChunkId)
        .containsExactly(1001L, 1000L, 1002L);
    assertThat(candidatesCaptor.getValue().get(0).getBestSparseScore()).isEqualTo(0.9);
    assertThat(candidatesCaptor.getValue().get(0).getBestDenseScore()).isEqualTo(0.8);
    assertThat(candidatesCaptor.getValue().get(1).getBestSparseScore()).isNull();
    assertThat(candidatesCaptor.getValue().get(2).getBestDenseScore()).isNull();
    assertThat(results).extracting(ChunkSearchResult::getChunkId).containsExactly(1002L);
  }

  @Test
  @DisplayName("retrieve() 空输入返回空列表")
  void emptyInputReturnsEmptyList() {
    assertThat(retrievalService.retrieve(null, 5)).isEmpty();
    assertThat(retrievalService.retrieve("", 5)).isEmpty();
    assertThat(retrievalService.retrieve("  ", 5)).isEmpty();
    assertThat(retrievalService.retrieve("query", 0)).isEmpty();
    assertThat(retrievalService.retrieve("query", -1)).isEmpty();
  }

  @Test
  @DisplayName("prepareRerankCandidates 跟踪真实 RRF 命中 ID")
  @SuppressWarnings("unchecked")
  void prepareRerankCandidatesTracksRealRrfHits() {
    Chunk child0 = child(1000L, 100L, 0, "相邻 child 0");
    Chunk child2 = child(1002L, 100L, 2, "相邻 child 2");

    when(chunkDao.findByParentChunkIdsAndChunkIndexes(List.of(100L), List.of(0, 2)))
        .thenReturn(List.of(child0, child2));

    RerankCandidateSet candidateSet =
        retrievalService.prepareRerankCandidates(
            List.of(new RrfFusionResult(1001L, 100L, 1, 0.9, "命中 child", 0.8, 0.7)));

    assertThat(candidateSet.allCandidates())
        .extracting(RrfFusionResult::getChunkId)
        .containsExactly(1001L, 1000L, 1002L);
    assertThat(candidateSet.rrfHitChunkIds()).containsExactly(1001L);
    assertThat(candidateSet.rrfHitChunkIds()).doesNotContain(1000L, 1002L);
  }

  @Test
  @DisplayName("prepareRerankCandidates 正确处理无 parent 的 child")
  void prepareRerankCandidatesHandlesNoParentChild() {
    RerankCandidateSet candidateSet =
        retrievalService.prepareRerankCandidates(
            List.of(new RrfFusionResult(9000L, 0.9, "孤立 child", null, null)));

    assertThat(candidateSet.allCandidates())
        .extracting(RrfFusionResult::getChunkId)
        .containsExactly(9000L);
    assertThat(candidateSet.rrfHitChunkIds()).containsExactly(9000L);
  }

  // ============================================================
  // retrieveEvidence
  // ============================================================

  @Test
  @DisplayName("retrieveEvidence() 空输入返回空列表")
  void retrieveEvidenceEmptyInputReturnsEmpty() {
    assertThat(retrievalService.retrieveEvidence(null, 5)).isEmpty();
    assertThat(retrievalService.retrieveEvidence("", 5)).isEmpty();
    assertThat(retrievalService.retrieveEvidence("  ", 5)).isEmpty();
    assertThat(retrievalService.retrieveEvidence("query", 0)).isEmpty();
    assertThat(retrievalService.retrieveEvidence("query", -1)).isEmpty();
  }

  @Test
  @SuppressWarnings("unchecked")
  @DisplayName("retrieveEvidence 返回完整 parent evidence (topN=3, limit=9)")
  void retrieveEvidenceReturnsFullParentEvidence() {
    float[] embedding = new float[] {0.1f};

    when(embeddingClient.embed("问题")).thenReturn(embedding);
    when(sparseQueryService.search(eq("问题"), eq(9)))
        .thenReturn(List.of(new SparseSearchResult(1001L, 100L, 0, 0.9, "child 1")));
    when(denseQueryService.search(any(float[].class), eq(9)))
        .thenReturn(List.of(new DenseSearchResult(1001L, 100L, 0, 0.8, "child 1")));
    when(rrfFusionService.fuse(any(), any(), eq(9)))
        .thenReturn(List.of(new RrfFusionResult(1001L, 100L, 0, 0.9, "child 1", 0.9, 0.8)));
    when(chunkDao.findByParentChunkIdsAndChunkIndexes(any(), any()))
        .thenReturn(Collections.emptyList());
    when(rerankService.rerank(any(), any()))
        .thenReturn(List.of(evidenceChild(1001L, 100L, "child 1", 0.9)));
    when(chunkDao.findParentContentsByIds(List.of(100L)))
        .thenReturn(List.of(new ParentChunkContent(100L, "full parent content")));

    List<ParentEvidenceResult> results = retrievalService.retrieveEvidence("问题", 3);

    assertThat(results).hasSize(1);
    assertThat(results.get(0).parentChunkId()).isEqualTo(100L);
    assertThat(results.get(0).content()).isEqualTo("full parent content");
    assertThat(results.get(0).matchedChildIds()).containsExactly(1001L);
  }

  @Test
  @SuppressWarnings("unchecked")
  @DisplayName("retrieveEvidence 缺失 parent 内容时跳过并补位")
  void retrieveEvidenceSkipsMissingContentAndFills() {
    float[] embedding = new float[] {0.1f};

    when(embeddingClient.embed("问题")).thenReturn(embedding);
    when(sparseQueryService.search(eq("问题"), eq(9)))
        .thenReturn(
            List.of(
                new SparseSearchResult(1001L, 100L, 0, 0.9, "child 1"),
                new SparseSearchResult(1002L, 200L, 0, 0.8, "child 2")));
    when(denseQueryService.search(any(float[].class), eq(9)))
        .thenReturn(
            List.of(
                new DenseSearchResult(1001L, 100L, 0, 0.5, "child 1"),
                new DenseSearchResult(1002L, 200L, 0, 0.6, "child 2")));
    when(rrfFusionService.fuse(any(), any(), eq(9)))
        .thenReturn(
            List.of(
                new RrfFusionResult(1001L, 100L, 0, 0.8, "child 1", 0.9, 0.5),
                new RrfFusionResult(1002L, 200L, 0, 0.7, "child 2", 0.8, 0.6)));
    when(chunkDao.findByParentChunkIdsAndChunkIndexes(any(), any()))
        .thenReturn(Collections.emptyList());
    when(rerankService.rerank(any(), any()))
        .thenReturn(
            List.of(
                evidenceChild(1001L, 100L, "child 1", 0.9),
                evidenceChild(1002L, 200L, "child 2", 0.8)));
    // p1 content is absent → skipped; p2 content is valid → fills position
    when(chunkDao.findParentContentsByIds(List.of(100L, 200L)))
        .thenReturn(List.of(new ParentChunkContent(200L, "parent 2 content")));

    List<ParentEvidenceResult> results = retrievalService.retrieveEvidence("问题", 3);

    assertThat(results).hasSize(1);
    assertThat(results.get(0).parentChunkId()).isEqualTo(200L);
    assertThat(results.get(0).content()).isEqualTo("parent 2 content");
  }

  @Test
  @SuppressWarnings("unchecked")
  @DisplayName("retrieveEvidence 全部 parent 无效返回空列表")
  void retrieveEvidenceAllParentsInvalidReturnsEmpty() {
    float[] embedding = new float[] {0.1f};

    when(embeddingClient.embed("问题")).thenReturn(embedding);
    when(sparseQueryService.search(eq("问题"), eq(9)))
        .thenReturn(List.of(new SparseSearchResult(1001L, 100L, 0, 0.9, "child 1")));
    when(denseQueryService.search(any(float[].class), eq(9)))
        .thenReturn(List.of(new DenseSearchResult(1001L, 100L, 0, 0.8, "child 1")));
    when(rrfFusionService.fuse(any(), any(), eq(9)))
        .thenReturn(List.of(new RrfFusionResult(1001L, 100L, 0, 0.9, "child 1", 0.9, 0.8)));
    when(chunkDao.findByParentChunkIdsAndChunkIndexes(any(), any()))
        .thenReturn(Collections.emptyList());
    when(rerankService.rerank(any(), any()))
        .thenReturn(List.of(evidenceChild(1001L, 100L, "child 1", 0.9)));
    // All parent content is blank → all skipped
    when(chunkDao.findParentContentsByIds(List.of(100L)))
        .thenReturn(List.of(new ParentChunkContent(100L, "  ")));

    List<ParentEvidenceResult> results = retrievalService.retrieveEvidence("问题", 3);

    assertThat(results).isEmpty();
  }

  @Test
  @SuppressWarnings("unchecked")
  @DisplayName("retrieveEvidence 截断到 topN 个 parent (topN=2, limit=6)")
  void retrieveEvidenceTruncatesToTopN() {
    float[] embedding = new float[] {0.1f};

    when(embeddingClient.embed("问题")).thenReturn(embedding);
    when(sparseQueryService.search(eq("问题"), eq(6)))
        .thenReturn(
            List.of(
                new SparseSearchResult(1001L, 100L, 0, 0.9, "c1"),
                new SparseSearchResult(1002L, 200L, 0, 0.8, "c2"),
                new SparseSearchResult(1003L, 300L, 0, 0.7, "c3")));
    when(denseQueryService.search(any(float[].class), eq(6))).thenReturn(List.of());
    when(rrfFusionService.fuse(any(), any(), eq(6)))
        .thenReturn(
            List.of(
                new RrfFusionResult(1001L, 100L, 0, 0.9, "c1", 0.9, null),
                new RrfFusionResult(1002L, 200L, 0, 0.8, "c2", 0.8, null),
                new RrfFusionResult(1003L, 300L, 0, 0.7, "c3", 0.7, null)));
    when(chunkDao.findByParentChunkIdsAndChunkIndexes(any(), any()))
        .thenReturn(Collections.emptyList());
    when(rerankService.rerank(any(), any()))
        .thenReturn(
            List.of(
                evidenceChild(1001L, 100L, "c1", 0.9),
                evidenceChild(1002L, 200L, "c2", 0.8),
                evidenceChild(1003L, 300L, "c3", 0.7)));
    when(chunkDao.findParentContentsByIds(List.of(100L, 200L, 300L)))
        .thenReturn(
            List.of(
                new ParentChunkContent(100L, "content 1"),
                new ParentChunkContent(200L, "content 2"),
                new ParentChunkContent(300L, "content 3")));

    List<ParentEvidenceResult> results = retrievalService.retrieveEvidence("问题", 2);

    assertThat(results).hasSize(2);
    assertThat(results).extracting(ParentEvidenceResult::parentChunkId).containsExactly(100L, 200L);
  }

  @Test
  @SuppressWarnings("unchecked")
  @DisplayName("retrieveEvidence RRF 返回空时返回空列表")
  void retrieveEvidenceRrfEmptyReturnsEmpty() {
    float[] embedding = new float[] {0.1f};
    when(embeddingClient.embed("问题")).thenReturn(embedding);
    when(sparseQueryService.search(eq("问题"), eq(9))).thenReturn(List.of());
    when(denseQueryService.search(any(float[].class), eq(9))).thenReturn(List.of());
    when(rrfFusionService.fuse(any(), any(), eq(9))).thenReturn(List.of());

    List<ParentEvidenceResult> results = retrievalService.retrieveEvidence("问题", 3);

    assertThat(results).isEmpty();
  }

  @Test
  @SuppressWarnings("unchecked")
  @DisplayName("retrieveEvidence 使用 3N 候选倍率 (topN=5, limit=15)")
  void retrieveEvidenceUses3NMultipliers() {
    float[] embedding = new float[] {0.1f};
    when(embeddingClient.embed("问题")).thenReturn(embedding);
    when(sparseQueryService.search(eq("问题"), eq(15))).thenReturn(List.of());
    when(denseQueryService.search(any(float[].class), eq(15))).thenReturn(List.of());
    when(rrfFusionService.fuse(any(), any(), eq(15))).thenReturn(List.of());

    List<ParentEvidenceResult> results = retrievalService.retrieveEvidence("问题", 5);
    assertThat(results).isEmpty();
  }

  // ============================================================
  // Helpers
  // ============================================================

  private static Chunk child(long chunkId, long parentChunkId, int chunkIndex, String content) {
    Chunk chunk = new Chunk();
    chunk.setChunkId(chunkId);
    chunk.setParentChunkId(parentChunkId);
    chunk.setChunkIndex(chunkIndex);
    chunk.setContent(content);
    return chunk;
  }

  /** Create a ChunkSearchResult without parentChunkId (for existing retrieve() tests). */
  private static ChunkSearchResult finalResult(long chunkId, String content, double rerankScore) {
    return ChunkSearchResult.fromRrfWithRerank(
        new RrfFusionResult(chunkId, 0.0, content, null, null), rerankScore);
  }

  /** Create a ChunkSearchResult with parentChunkId (for evidence tests). */
  private static ChunkSearchResult evidenceChild(
      long chunkId, long parentChunkId, String content, double rerankScore) {
    return ChunkSearchResult.fromRrfWithRerank(
        new RrfFusionResult(chunkId, parentChunkId, null, 0.0, content, null, null), rerankScore);
  }
}
