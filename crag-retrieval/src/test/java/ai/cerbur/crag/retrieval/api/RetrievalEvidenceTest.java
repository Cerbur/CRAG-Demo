package ai.cerbur.crag.retrieval.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.cerbur.crag.retrieval.api.RetrievalService.EvidenceCandidate;
import ai.cerbur.crag.retrieval.api.result.ChunkSearchResult;
import ai.cerbur.crag.retrieval.api.result.ParentEvidenceResult;
import ai.cerbur.crag.retrieval.bo.ChunkBO;
import ai.cerbur.crag.retrieval.result.RrfFusionResult;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Retrieval Evidence 聚合逻辑纯单元测试.
 *
 * <p>覆盖 ParentEvidenceResult 不变量、饱和乘法、Evidence 候选聚合、parent 排名、 matched child 稳定去重、Rerank
 * 部分返回与主动截断语义. 所有测试不依赖 Spring、数据库或 Sidecar.
 *
 * @since 2026-06-20
 */
@DisplayName("Retrieval Evidence 聚合逻辑")
class RetrievalEvidenceTest {

  // ============================================================
  // ParentEvidenceResult invariants
  // ============================================================

  @Nested
  @DisplayName("ParentEvidenceResult 不变量")
  class ParentEvidenceResultInvariants {

    @Test
    @DisplayName("合法参数创建成功")
    void validConstruction() {
      ParentEvidenceResult result =
          new ParentEvidenceResult("p1", "parent content", List.of("c1", "c2"));

      assertThat(result.parentChunkId()).isEqualTo("p1");
      assertThat(result.content()).isEqualTo("parent content");
      assertThat(result.matchedChildIds()).containsExactly("c1", "c2");
    }

    @Test
    @DisplayName("matchedChildIds 防御性复制")
    void defensiveCopyOfMatchedChildIds() {
      List<String> mutable = new java.util.ArrayList<>(List.of("c1"));
      ParentEvidenceResult result = new ParentEvidenceResult("p1", "content", mutable);

      // Modify the original list — should not affect the record
      mutable.add("c2");

      assertThat(result.matchedChildIds()).containsExactly("c1");
    }

    @Test
    @DisplayName("matchedChildIds 不可修改")
    void matchedChildIdsUnmodifiable() {
      ParentEvidenceResult result = new ParentEvidenceResult("p1", "content", List.of("c1"));

      assertThatThrownBy(() -> result.matchedChildIds().add("c2"))
          .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("parentChunkId 为 null 抛出异常")
    void nullParentChunkIdThrows() {
      assertThatThrownBy(() -> new ParentEvidenceResult(null, "content", List.of("c1")))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("parentChunkId");
    }

    @Test
    @DisplayName("parentChunkId 为 blank 抛出异常")
    void blankParentChunkIdThrows() {
      assertThatThrownBy(() -> new ParentEvidenceResult("  ", "content", List.of("c1")))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("parentChunkId");
    }

    @Test
    @DisplayName("content 为 null 抛出异常")
    void nullContentThrows() {
      assertThatThrownBy(() -> new ParentEvidenceResult("p1", null, List.of("c1")))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("content");
    }

    @Test
    @DisplayName("content 为 blank 抛出异常")
    void blankContentThrows() {
      assertThatThrownBy(() -> new ParentEvidenceResult("p1", "  ", List.of("c1")))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("content");
    }

    @Test
    @DisplayName("matchedChildIds 为 null 抛出异常")
    void nullMatchedChildIdsThrows() {
      assertThatThrownBy(() -> new ParentEvidenceResult("p1", "content", null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("matchedChildIds");
    }

    @Test
    @DisplayName("matchedChildIds 为空抛出异常")
    void emptyMatchedChildIdsThrows() {
      assertThatThrownBy(() -> new ParentEvidenceResult("p1", "content", Collections.emptyList()))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("matchedChildIds");
    }
  }

  // ============================================================
  // saturatingMultiply
  // ============================================================

  @Nested
  @DisplayName("饱和乘法")
  class SaturatingMultiply {

    @Test
    @DisplayName("正常乘法")
    void normalMultiplication() {
      assertThat(RetrievalService.saturatingMultiply(5, 3)).isEqualTo(15);
      assertThat(RetrievalService.saturatingMultiply(1, 3)).isEqualTo(3);
      assertThat(RetrievalService.saturatingMultiply(100, 3)).isEqualTo(300);
    }

    @Test
    @DisplayName("溢出时饱和到 Integer.MAX_VALUE")
    void overflowSaturatesToMaxValue() {
      int result = RetrievalService.saturatingMultiply(Integer.MAX_VALUE / 2 + 1, 3);
      assertThat(result).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    @DisplayName("大数不溢出")
    void largeNumberDoesNotOverflow() {
      int result = RetrievalService.saturatingMultiply(1_000_000_000, 3);
      // 3_000_000_000 > Integer.MAX_VALUE but < Long.MAX_VALUE
      assertThat(result).isEqualTo(Integer.MAX_VALUE);
    }
  }

  // ============================================================
  // aggregateEvidenceCandidates — basic behavior
  // ============================================================

  @Nested
  @DisplayName("Evidence 候选聚合 — 基本行为")
  class BasicAggregation {

    @Test
    @DisplayName("空输入返回空列表")
    void emptyInputReturnsEmpty() {
      assertThat(RetrievalService.aggregateEvidenceCandidates(null, Set.of(), 5)).isEmpty();
      assertThat(RetrievalService.aggregateEvidenceCandidates(Collections.emptyList(), Set.of(), 5))
          .isEmpty();
    }

    @Test
    @DisplayName("单个 parent 单个 RRF 命中 child")
    void singleParentSingleRrfHit() {
      List<ChunkSearchResult> reranked = List.of(childResult("c1", "p1", "命中内容"));
      LinkedHashSet<String> rrfHits = setOf("c1");

      List<EvidenceCandidate> candidates =
          RetrievalService.aggregateEvidenceCandidates(reranked, rrfHits, 5);

      assertThat(candidates).hasSize(1);
      assertThat(candidates.get(0).parentChunkId()).isEqualTo("p1");
      assertThat(candidates.get(0).matchedChildIds()).containsExactly("c1");
      assertThat(candidates.get(0).bestRank()).isEqualTo(0);
    }

    @Test
    @DisplayName("多个 parent 按最佳 Rerank 名次排序")
    void multiParentRankedByBestRerankRank() {
      List<ChunkSearchResult> reranked =
          List.of(
              childResult("c_a1", "pA", "A1"), // rank 0
              childResult("c_b1", "pB", "B1"), // rank 1
              childResult("c_a2", "pA", "A2"), // rank 2 — pA already has rank 0
              childResult("c_c1", "pC", "C1")); // rank 3
      LinkedHashSet<String> rrfHits = setOf("c_a1", "c_b1", "c_a2", "c_c1");

      List<EvidenceCandidate> candidates =
          RetrievalService.aggregateEvidenceCandidates(reranked, rrfHits, 5);

      assertThat(candidates).hasSize(3);
      assertThat(candidates)
          .extracting(EvidenceCandidate::parentChunkId)
          .containsExactly("pA", "pB", "pC");
      assertThat(candidates.get(0).bestRank()).isEqualTo(0); // pA via c_a1
      assertThat(candidates.get(1).bestRank()).isEqualTo(1); // pB via c_b1
      assertThat(candidates.get(2).bestRank()).isEqualTo(3); // pC via c_c1
    }

    @Test
    @DisplayName("同一 parent 多个 RRF 命中 child")
    void sameParentMultipleRrfHits() {
      List<ChunkSearchResult> reranked =
          List.of(
              childResult("c1", "p1", "C1"), // rank 0
              childResult("c2", "p2", "C2"), // rank 1
              childResult("c3", "p1", "C3")); // rank 2 — same parent p1
      LinkedHashSet<String> rrfHits = setOf("c1", "c2", "c3");

      List<EvidenceCandidate> candidates =
          RetrievalService.aggregateEvidenceCandidates(reranked, rrfHits, 5);

      assertThat(candidates).hasSize(2);
      assertThat(candidates.get(0).parentChunkId()).isEqualTo("p1");
      assertThat(candidates.get(0).bestRank()).isEqualTo(0);
      assertThat(candidates.get(0).matchedChildIds())
          .containsExactly("c1", "c3"); // in rerank order
    }
  }

  // ============================================================
  // aggregateEvidenceCandidates — real vs adjacent
  // ============================================================

  @Nested
  @DisplayName("Evidence 候选聚合 — 真实命中 vs 相邻扩展")
  class RealVsAdjacent {

    @Test
    @DisplayName("相邻扩展 child 不进入 matchedChildIds")
    void adjacentChildNotInMatchedChildIds() {
      List<ChunkSearchResult> reranked =
          List.of(
              childResult("c_rrf", "p1", "RRF 命中"), // rank 0 — real RRF hit
              childResult("c_adj", "p1", "相邻扩展")); // rank 1 — adjacent
      LinkedHashSet<String> rrfHits = setOf("c_rrf"); // only c_rrf is real hit

      List<EvidenceCandidate> candidates =
          RetrievalService.aggregateEvidenceCandidates(reranked, rrfHits, 5);

      assertThat(candidates).hasSize(1);
      assertThat(candidates.get(0).parentChunkId()).isEqualTo("p1");
      assertThat(candidates.get(0).matchedChildIds()).containsExactly("c_rrf");
    }

    @Test
    @DisplayName("相邻扩展 child 可影响 parent 排名")
    void adjacentChildInfluencesParentRank() {
      // p1 has an adjacent child at rank 0 (best rank = 0)
      // p2 has an RRF hit child at rank 1 (best rank = 1)
      // p1 should be ranked above p2 even though its RRF hit is at rank 2
      List<ChunkSearchResult> reranked =
          List.of(
              childResult("c_adj_p1", "p1", "p1 相邻扩展"), // rank 0 — adjacent
              childResult("c_rrf_p2", "p2", "p2 RRF 命中"), // rank 1 — RRF hit
              childResult("c_rrf_p1", "p1", "p1 RRF 命中")); // rank 2 — RRF hit
      LinkedHashSet<String> rrfHits = setOf("c_rrf_p2", "c_rrf_p1");

      List<EvidenceCandidate> candidates =
          RetrievalService.aggregateEvidenceCandidates(reranked, rrfHits, 5);

      // p1 is ranked first because its adjacent child at rank 0 gives it bestRank=0
      assertThat(candidates)
          .extracting(EvidenceCandidate::parentChunkId)
          .containsExactly("p1", "p2");
      assertThat(candidates.get(0).bestRank()).isEqualTo(0);
      assertThat(candidates.get(0).matchedChildIds()).containsExactly("c_rrf_p1");
    }

    @Test
    @DisplayName("只有相邻扩展的 parent 不返回")
    void adjacentOnlyParentExcluded() {
      List<ChunkSearchResult> reranked =
          List.of(
              childResult("c_adj", "p1", "仅相邻"), // rank 0 — adjacent only
              childResult("c_rrf", "p2", "真实命中")); // rank 1 — real hit
      LinkedHashSet<String> rrfHits = setOf("c_rrf"); // p1 has no real hit

      List<EvidenceCandidate> candidates =
          RetrievalService.aggregateEvidenceCandidates(reranked, rrfHits, 5);

      assertThat(candidates).hasSize(1);
      assertThat(candidates.get(0).parentChunkId()).isEqualTo("p2");
    }
  }

  // ============================================================
  // aggregateEvidenceCandidates — active truncation
  // ============================================================

  @Nested
  @DisplayName("Evidence 候选聚合 — 主动截断")
  class ActiveTruncation {

    @Test
    @DisplayName("3N 窗口截断排除的 child 不进入 matchedChildIds")
    void truncatedChildNotInMatchedChildIds() {
      List<ChunkSearchResult> reranked =
          List.of(
              childResult("c1", "p1", "C1"), // rank 0 — in window
              childResult("c2", "p2", "C2"), // rank 1 — in window
              childResult("c3", "p1", "C3")); // rank 2 — outside window (windowN=2)
      LinkedHashSet<String> rrfHits = setOf("c1", "c2", "c3");

      List<EvidenceCandidate> candidates =
          RetrievalService.aggregateEvidenceCandidates(reranked, rrfHits, 2);

      // c3 (rank 2) is outside the 2-window, so it doesn't appear in p1's matchedChildIds
      assertThat(candidates).hasSize(2);
      assertThat(candidates.get(0).parentChunkId()).isEqualTo("p1");
      assertThat(candidates.get(0).matchedChildIds()).containsExactly("c1"); // c3 truncated
    }

    @Test
    @DisplayName("真实命中 child 被截断只剩相邻 child 时 parent 丢弃")
    void parentWithOnlyTruncatedRealHitsIsDropped() {
      // p1's real RRF hit c_rrf is at rank 1, outside windowN=1
      // Only c_adj (adjacent, rank 0) is inside the window
      List<ChunkSearchResult> reranked =
          List.of(
              childResult("c_adj", "p1", "相邻扩展"), // rank 0 — adjacent
              childResult("c_rrf", "p1", "RRF 命中"), // rank 1 — real hit but outside window
              childResult("c2", "p2", "C2")); // rank 2 — also outside
      LinkedHashSet<String> rrfHits = setOf("c_rrf", "c2");

      List<EvidenceCandidate> candidates =
          RetrievalService.aggregateEvidenceCandidates(reranked, rrfHits, 1);

      // p1 is dropped because its only real hit (c_rrf) is outside the window
      // p2 is dropped because its only child (c2) is outside the window
      assertThat(candidates).isEmpty();
    }
  }

  // ============================================================
  // aggregateEvidenceCandidates — edge cases
  // ============================================================

  @Nested
  @DisplayName("Evidence 候选聚合 — 边界情况")
  class EdgeCases {

    @Test
    @DisplayName("child 无有效 parentChunkId 时跳过")
    void childWithBlankParentIdSkipped() {
      List<ChunkSearchResult> reranked =
          List.of(
              childResult("c1", null, "无 parent"), // skipped
              childResult("c2", "", "blank parent"), // skipped
              childResult("c3", "  ", "blank parent"), // skipped
              childResult("c4", "p1", "有效")); // rank 0 for p1
      LinkedHashSet<String> rrfHits = setOf("c1", "c2", "c3", "c4");

      List<EvidenceCandidate> candidates =
          RetrievalService.aggregateEvidenceCandidates(reranked, rrfHits, 5);

      assertThat(candidates).hasSize(1);
      assertThat(candidates.get(0).parentChunkId()).isEqualTo("p1");
      assertThat(candidates.get(0).matchedChildIds()).containsExactly("c4");
    }

    @Test
    @DisplayName("matched child 稳定去重")
    void matchedChildStableDedup() {
      // Same chunkId appears multiple times (shouldn't normally happen but must be handled)
      // Since we filter by realRrfHitChunkIds and use LinkedHashSet per parent, duplicates are
      // removed
      List<ChunkSearchResult> reranked =
          List.of(
              childResult("c1", "p1", "C1"),
              childResult("c1", "p1", "C1 dupe"), // same chunk ID, same parent
              childResult("c2", "p1", "C2"));
      LinkedHashSet<String> rrfHits = setOf("c1", "c2");

      List<EvidenceCandidate> candidates =
          RetrievalService.aggregateEvidenceCandidates(reranked, rrfHits, 5);

      assertThat(candidates).hasSize(1);
      // c1 appears only once due to LinkedHashSet dedup
      assertThat(candidates.get(0).matchedChildIds()).containsExactly("c1", "c2");
    }

    @Test
    @DisplayName("Rerank 回退 (空结果) 返回空候选")
    void rerankFallbackEmptyResults() {
      List<EvidenceCandidate> candidates =
          RetrievalService.aggregateEvidenceCandidates(Collections.emptyList(), Set.of(), 5);

      assertThat(candidates).isEmpty();
    }

    @Test
    @DisplayName("Rerank 部分返回 — 未评分候选继承现有稳定尾排语义")
    void rerankPartialReturnPreservesTailOrder() {
      // RerankService gives scores to some candidates, others get 0.0
      // Unscored candidates maintain their original relative order after scored ones.
      // This is already handled by RerankService; here we verify that Evidence
      // correctly consumes whatever order RerankService produces.

      // Simulate: c1 (scored 0.9) → c2 (scored 0.5) → c3 (unscored, 0.0) → c4 (unscored, 0.0)
      // The order comes from RerankService sorting by rerank score descending,
      // with unscored maintaining original relative order
      List<ChunkSearchResult> reranked =
          List.of(
              childResult("c1", "p1", "scored high"),
              childResult("c2", "p2", "scored low"),
              childResult("c3", "p1", "unscored — original order preserved"),
              childResult("c4", "p1", "unscored — original order preserved"));
      LinkedHashSet<String> rrfHits = setOf("c1", "c2", "c3", "c4");

      List<EvidenceCandidate> candidates =
          RetrievalService.aggregateEvidenceCandidates(reranked, rrfHits, 5);

      // p1 has children at ranks 0, 2, 3 → best rank = 0
      // p2 has child at rank 1 → best rank = 1
      assertThat(candidates)
          .extracting(EvidenceCandidate::parentChunkId)
          .containsExactly("p1", "p2");
      assertThat(candidates.get(0).matchedChildIds()).containsExactly("c1", "c3", "c4");
      assertThat(candidates.get(1).matchedChildIds()).containsExactly("c2");
    }
  }

  // ============================================================
  // aggregateEvidenceCandidates — RRF hit ordering
  // ============================================================

  @Nested
  @DisplayName("Evidence 候选 — RRF 命中有序性")
  class RrfHitOrdering {

    @Test
    @DisplayName("realRrfHitChunkIds 保持 RRF 融合顺序")
    void rrfHitIdsMaintainFusionOrder() {
      // The order in realRrfHitChunkIds is the RRF fusion order (by RRF score descending).
      // This order is preserved through LinkedHashSet.
      LinkedHashSet<String> rrfHits = new LinkedHashSet<>();
      rrfHits.add("c3"); // highest RRF score
      rrfHits.add("c1"); // medium RRF score
      rrfHits.add("c2"); // lowest RRF score

      assertThat(rrfHits).containsExactly("c3", "c1", "c2");
    }

    @Test
    @DisplayName("matchedChildIds 按 Rerank 顺序排列")
    void matchedChildIdsOrderedByRerankRank() {
      // c2 appears earlier in rerank (rank 0), c1 appears later (rank 2)
      List<ChunkSearchResult> reranked =
          List.of(
              childResult("c2", "p1", "C2"), // rank 0
              childResult("cx", "p2", "CX"), // rank 1
              childResult("c1", "p1", "C1")); // rank 2
      LinkedHashSet<String> rrfHits = setOf("c1", "c2"); // RRF order

      List<EvidenceCandidate> candidates =
          RetrievalService.aggregateEvidenceCandidates(reranked, rrfHits, 5);

      assertThat(candidates.get(0).parentChunkId()).isEqualTo("p1");
      // matchedChildIds follow rerank order: c2 (rank 0) before c1 (rank 2)
      assertThat(candidates.get(0).matchedChildIds()).containsExactly("c2", "c1");
    }
  }

  // ============================================================
  // aggregateEvidenceCandidates — parent candidate limits
  // ============================================================

  @Nested
  @DisplayName("Evidence 候选 — parent 候选数限制")
  class ParentCandidateLimits {

    @Test
    @DisplayName("候选高度聚集时返回少于 topN 个 parent")
    void highClusterReturnsFewerParents() {
      // All children belong to p1, so only 1 parent even with topN=5
      List<ChunkSearchResult> reranked =
          List.of(
              childResult("c1", "p1", "C1"),
              childResult("c2", "p1", "C2"),
              childResult("c3", "p1", "C3"),
              childResult("c4", "p1", "C4"),
              childResult("c5", "p1", "C5"));
      LinkedHashSet<String> rrfHits = setOf("c1", "c2", "c3", "c4", "c5");

      List<EvidenceCandidate> candidates =
          RetrievalService.aggregateEvidenceCandidates(reranked, rrfHits, 5);

      assertThat(candidates).hasSize(1);
    }

    @Test
    @DisplayName("多 parent 散列时窗口限制 parent 数量")
    void windowLimitsParentCount() {
      List<ChunkSearchResult> reranked = new java.util.ArrayList<>();
      LinkedHashSet<String> rrfHits = new LinkedHashSet<>();
      for (int i = 0; i < 10; i++) {
        String pid = "p" + i;
        String cid = "c" + i;
        reranked.add(childResult(cid, pid, "content " + i));
        rrfHits.add(cid);
      }

      // windowN=5: only first 5 children are in the evidence window
      List<EvidenceCandidate> candidates =
          RetrievalService.aggregateEvidenceCandidates(reranked, rrfHits, 5);

      assertThat(candidates).hasSize(5);
      assertThat(candidates)
          .extracting(EvidenceCandidate::parentChunkId)
          .containsExactly("p0", "p1", "p2", "p3", "p4");
    }

    @Test
    @DisplayName("不截断时返回所有有真实命中的 parent")
    void noTruncationReturnsAllParents() {
      List<ChunkSearchResult> reranked = new java.util.ArrayList<>();
      LinkedHashSet<String> rrfHits = new LinkedHashSet<>();
      for (int i = 0; i < 10; i++) {
        String pid = "p" + i;
        String cid = "c" + i;
        reranked.add(childResult(cid, pid, "content " + i));
        rrfHits.add(cid);
      }

      // windowN large enough for all
      List<EvidenceCandidate> candidates =
          RetrievalService.aggregateEvidenceCandidates(reranked, rrfHits, 20);

      assertThat(candidates).hasSize(10);
    }
  }

  // ============================================================
  // Helpers
  // ============================================================

  /** 创建带有指定 parentChunkId 的 ChunkSearchResult. */
  private static ChunkSearchResult childResult(
      String chunkId, String parentChunkId, String content) {
    ChunkBO bo = new ChunkBO(chunkId, parentChunkId, null, content);
    RrfFusionResult rrf = new RrfFusionResult(bo, 0.5, null, null);
    return ChunkSearchResult.fromRrfWithRerank(rrf, 0.5);
  }

  /** 创建 LinkedHashSet 辅助方法. */
  @SafeVarargs
  private static <T> LinkedHashSet<T> setOf(T... elements) {
    LinkedHashSet<T> set = new LinkedHashSet<>();
    for (T e : elements) {
      set.add(e);
    }
    return set;
  }
}
