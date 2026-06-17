package ai.cerbur.crag.retrieval.service;

import ai.cerbur.crag.retrieval.dense.DenseQueryService;
import ai.cerbur.crag.retrieval.embedding.EmbeddingClient;
import ai.cerbur.crag.retrieval.rerank.RerankService;
import ai.cerbur.crag.retrieval.rrf.RrfFusionService;
import ai.cerbur.crag.retrieval.sparse.SparseQueryService;
import ai.cerbur.crag.storage.ChunkDao;
import ai.cerbur.crag.storage.entity.Chunk;
import ai.cerbur.crag.retrieval.result.ChunkSearchResult;
import ai.cerbur.crag.retrieval.result.DenseSearchResult;
import ai.cerbur.crag.retrieval.result.RrfFusionResult;
import ai.cerbur.crag.retrieval.result.SparseSearchResult;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * RetrievalService 单元测试 —— 验证检索门面内 RRF 与 Rerank 候选扩展编排.
 *
 * 下游服务通过 Mockito 隔离，聚焦 child 维度 RRF、相邻 child 扩展和最终 topN 截断行为.
 *
 * @since 2026-06-17
 */
@DisplayName("RetrievalService 检索编排服务")
@ExtendWith(MockitoExtension.class)
class RetrievalServiceTest {

    @Mock
    private EmbeddingClient embeddingClient;

    @Mock
    private SparseQueryService sparseQueryService;

    @Mock
    private DenseQueryService denseQueryService;

    @Mock
    private RrfFusionService rrfFusionService;

    @Mock
    private RerankService rerankService;

    @Mock
    private ChunkDao chunkDao;

    @InjectMocks
    private RetrievalService retrievalService;

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("RRF 保持 child 维度，Rerank 使用 top child 及相邻 child")
    void rrfStaysAtChildLevelAndRerankUsesAdjacentChildren() {
        float[] embedding = new float[] {0.1f, 0.2f};
        Chunk child0 = child("c0", "p1", 0, "前一个 child");
        Chunk child1 = child("c1", "p1", 1, "命中的 child");
        Chunk child2 = child("c2", "p1", 2, "后一个 child");
        ArgumentCaptor<List<RrfFusionResult>> candidatesCaptor = ArgumentCaptor.forClass(List.class);

        when(embeddingClient.embed("问题")).thenReturn(embedding);
        when(sparseQueryService.search("问题", 3)).thenReturn(List.of(
            new SparseSearchResult("c1", "p1", 1, 0.9, "命中的 child")
        ));
        when(denseQueryService.search(embedding, 3)).thenReturn(List.of(
            new DenseSearchResult("c1", "p1", 1, 0.8, "命中的 child")
        ));
        when(rrfFusionService.fuse(any(), any(), eq(1))).thenReturn(List.of(
            new RrfFusionResult("c1", "p1", 1, 2.0 / 61.0, "命中的 child", 0.9, 0.8)
        ));
        when(chunkDao.findByParentChunkIdsAndChunkIndexes(List.of("p1"), List.of(0, 2)))
            .thenReturn(List.of(child0, child2));
        when(rerankService.rerank(eq("问题"), candidatesCaptor.capture())).thenReturn(List.of(
            finalResult("c2", "后一个 child", 0.91),
            finalResult("c1", "命中的 child", 0.80),
            finalResult("c0", "前一个 child", 0.70)
        ));

        List<ChunkSearchResult> results = retrievalService.retrieve("问题", 1);

        assertThat(candidatesCaptor.getValue())
            .extracting(RrfFusionResult::getChunkId)
            .containsExactly("c1", "c0", "c2");
        assertThat(candidatesCaptor.getValue().get(0).getBestSparseScore()).isEqualTo(0.9);
        assertThat(candidatesCaptor.getValue().get(0).getBestDenseScore()).isEqualTo(0.8);
        assertThat(candidatesCaptor.getValue().get(1).getBestSparseScore()).isNull();
        assertThat(candidatesCaptor.getValue().get(2).getBestDenseScore()).isNull();
        assertThat(results).extracting(ChunkSearchResult::getChunkId).containsExactly("c2");
    }

    private static Chunk child(String chunkId, String parentChunkId, int chunkIndex, String content) {
        Chunk chunk = new Chunk();
        chunk.setChunkId(chunkId);
        chunk.setParentChunkId(parentChunkId);
        chunk.setChunkIndex(chunkIndex);
        chunk.setContent(content);
        return chunk;
    }

    private static ChunkSearchResult finalResult(String chunkId, String content, double rerankScore) {
        return ChunkSearchResult.fromRrfWithRerank(
            new RrfFusionResult(chunkId, 0.0, content, null, null),
            rerankScore
        );
    }
}
