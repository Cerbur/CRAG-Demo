package ai.cerbur.crag.retrieval.rerank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.cerbur.crag.retrieval.rerank.client.RerankClient;
import ai.cerbur.crag.retrieval.rerank.client.RerankClient.RerankResult;
import ai.cerbur.crag.retrieval.result.ChunkSearchResult;
import ai.cerbur.crag.retrieval.result.RrfFusionResult;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * RerankService 单元测试 —— 验证语义重排序逻辑、ChunkSearchResult 组装、上游得分保留.
 *
 * <p>RerankClient 通过 Mockito 隔离，聚焦服务层的编排逻辑和 fromRrfWithRerank() 调用.
 *
 * @since 2026-06-15
 */
@DisplayName("RerankService 重排序服务")
@ExtendWith(MockitoExtension.class)
class RerankServiceTest {

  @Mock private RerankClient rerankClient;

  @InjectMocks private RerankService rerankService;

  /** 创建模拟的 RRF 融合结果（作为 rerank 的输入）. */
  private RrfFusionResult rrf(String id, String content) {
    return new RrfFusionResult(id, 0.5, content, null, null);
  }

  @Nested
  @DisplayName("无效输入保护")
  class InvalidInputProtection {

    @Test
    @DisplayName("query 为 null → 返回空列表，不调用 RerankClient")
    void nullQueryReturnsEmpty() {
      List<RrfFusionResult> chunks = List.of(rrf("c1", "内容"));
      List<ChunkSearchResult> results = rerankService.rerank(null, chunks);

      assertThat(results).isEmpty();
      verifyNoInteractions(rerankClient);
    }

    @Test
    @DisplayName("query 为空字符串 → 返回空列表")
    void blankQueryReturnsEmpty() {
      List<ChunkSearchResult> results = rerankService.rerank("", List.of(rrf("c1", "内容")));
      assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("query 为纯空白 → 返回空列表")
    void whitespaceQueryReturnsEmpty() {
      List<ChunkSearchResult> results = rerankService.rerank("   ", List.of(rrf("c1", "内容")));
      assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("chunks 为 null → 返回空列表")
    void nullChunksReturnsEmpty() {
      List<ChunkSearchResult> results = rerankService.rerank("测试问题", null);
      assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("chunks 为空列表 → 返回空列表")
    void emptyChunksReturnsEmpty() {
      List<ChunkSearchResult> results = rerankService.rerank("测试问题", Collections.emptyList());
      assertThat(results).isEmpty();
    }
  }

  @Nested
  @DisplayName("正常重排序")
  class NormalReranking {

    @Test
    @DisplayName("正常调用 → 按 rerank score 降序重排，且 rerankScore 正确写入 ChunkSearchResult")
    void delegatesToClientAndReordersByScoreDescending() {
      List<RrfFusionResult> chunks =
          List.of(rrf("c1", "文档A的内容"), rrf("c2", "文档B的内容"), rrf("c3", "文档C的内容"));
      when(rerankClient.rerank(any(), any()))
          .thenReturn(
              List.of(
                  new RerankResult(2, 0.95f),
                  new RerankResult(1, 0.70f),
                  new RerankResult(0, 0.30f)));

      List<ChunkSearchResult> results = rerankService.rerank("查询问题", chunks);

      assertThat(results).hasSize(3);
      // c3 (index 2, rerank 0.95) should be first
      assertThat(results.get(0).getChunkId()).isEqualTo("c3");
      assertThat(results.get(0).getContent()).isEqualTo("文档C的内容");
      assertThat(results.get(0).getRerankScore()).isCloseTo(0.95, within(0.001));
      // c2 (index 1, rerank 0.70) should be second
      assertThat(results.get(1).getChunkId()).isEqualTo("c2");
      assertThat(results.get(1).getRerankScore()).isCloseTo(0.70, within(0.001));
      // c1 (index 0, rerank 0.30) should be last
      assertThat(results.get(2).getChunkId()).isEqualTo("c1");
      assertThat(results.get(2).getRerankScore()).isCloseTo(0.30, within(0.001));
    }

    @Test
    @DisplayName("单个 chunk → 正确组装 ChunkSearchResult")
    void singleChunkReturnsItself() {
      List<RrfFusionResult> chunks = List.of(rrf("c1", "唯一文档"));
      when(rerankClient.rerank(any(), any())).thenReturn(List.of(new RerankResult(0, 0.85f)));

      List<ChunkSearchResult> results = rerankService.rerank("问题", chunks);

      assertThat(results).hasSize(1);
      assertThat(results.get(0).getChunkId()).isEqualTo("c1");
      assertThat(results.get(0).getRerankScore()).isCloseTo(0.85, within(0.001));
    }

    @Test
    @DisplayName("上游得分（sparse/dense/rrf）在 rerank 后通过 fromRrfWithRerank 完整保留")
    void upstreamScoresPreservedAfterRerank() {
      RrfFusionResult chunk1 = new RrfFusionResult("c1", 0.032, "内容A", 0.85, 0.92);
      RrfFusionResult chunk2 = new RrfFusionResult("c2", 0.016, "内容B", 0.72, null);
      List<RrfFusionResult> chunks = List.of(chunk1, chunk2);
      when(rerankClient.rerank(any(), any()))
          .thenReturn(List.of(new RerankResult(1, 0.88f), new RerankResult(0, 0.45f)));

      List<ChunkSearchResult> results = rerankService.rerank("问题", chunks);

      // c2 comes first (rerank 0.88), c1 second (rerank 0.45)
      assertThat(results.get(0).getChunkId()).isEqualTo("c2");
      assertThat(results.get(0).getRerankScore()).isCloseTo(0.88, within(0.001));
      assertThat(results.get(0).getRrfScore()).isEqualTo(0.016);
      assertThat(results.get(0).getSparseScore()).isEqualTo(0.72);
      assertThat(results.get(0).getDenseScore()).isNull();

      assertThat(results.get(1).getChunkId()).isEqualTo("c1");
      assertThat(results.get(1).getRerankScore()).isCloseTo(0.45, within(0.001));
      assertThat(results.get(1).getRrfScore()).isEqualTo(0.032);
      assertThat(results.get(1).getSparseScore()).isEqualTo(0.85);
      assertThat(results.get(1).getDenseScore()).isEqualTo(0.92);
    }
  }

  @Nested
  @DisplayName("降级与边界")
  class FallbackAndEdgeCases {

    @Test
    @DisplayName("RerankClient 返回空 → 降级返回原始顺序，rerankScore 为 0.0")
    void clientReturnsEmptyFallsBackToOriginalOrder() {
      List<RrfFusionResult> chunks = List.of(rrf("c1", "第一"), rrf("c2", "第二"), rrf("c3", "第三"));
      when(rerankClient.rerank(any(), any())).thenReturn(Collections.emptyList());

      List<ChunkSearchResult> results = rerankService.rerank("问题", chunks);

      assertThat(results).hasSize(3);
      assertThat(results.get(0).getChunkId()).isEqualTo("c1");
      assertThat(results.get(1).getChunkId()).isEqualTo("c2");
      assertThat(results.get(2).getChunkId()).isEqualTo("c3");
    }

    @Test
    @DisplayName("RerankClient 返回部分结果 → 未匹配的 rerankScore 为 0，已匹配的正确")
    void partialRerankResultsOnlyReorderMatchedIndices() {
      List<RrfFusionResult> chunks = List.of(rrf("c1", "第一"), rrf("c2", "第二"), rrf("c3", "第三"));
      when(rerankClient.rerank(any(), any()))
          .thenReturn(List.of(new RerankResult(2, 0.90f), new RerankResult(0, 0.40f)));

      List<ChunkSearchResult> results = rerankService.rerank("问题", chunks);

      assertThat(results).hasSize(3);
      assertThat(results.get(0).getChunkId()).isEqualTo("c3");
      assertThat(results.get(0).getRerankScore()).isCloseTo(0.90, within(0.001));
      assertThat(results.get(1).getChunkId()).isEqualTo("c1");
      assertThat(results.get(1).getRerankScore()).isCloseTo(0.40, within(0.001));
      assertThat(results.get(2).getChunkId()).isEqualTo("c2");
      assertThat(results.get(2).getRerankScore()).isCloseTo(0.0, within(0.001));
    }

    @Test
    @DisplayName("Rerank 分数全部相等 → 保持原始相对顺序")
    void equalScoresPreserveOriginalOrder() {
      List<RrfFusionResult> chunks = List.of(rrf("c1", "A"), rrf("c2", "B"), rrf("c3", "C"));
      when(rerankClient.rerank(any(), any()))
          .thenReturn(
              List.of(
                  new RerankResult(0, 0.50f),
                  new RerankResult(1, 0.50f),
                  new RerankResult(2, 0.50f)));

      List<ChunkSearchResult> results = rerankService.rerank("问题", chunks);

      assertThat(results).hasSize(3);
      assertThat(results.get(0).getChunkId()).isEqualTo("c1");
      assertThat(results.get(0).getRerankScore()).isCloseTo(0.50, within(0.001));
      assertThat(results.get(1).getChunkId()).isEqualTo("c2");
      assertThat(results.get(1).getRerankScore()).isCloseTo(0.50, within(0.001));
      assertThat(results.get(2).getChunkId()).isEqualTo("c3");
      assertThat(results.get(2).getRerankScore()).isCloseTo(0.50, within(0.001));
    }
  }
}
