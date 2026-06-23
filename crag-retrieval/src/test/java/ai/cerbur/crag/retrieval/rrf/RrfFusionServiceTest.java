package ai.cerbur.crag.retrieval.rrf;

import static org.assertj.core.api.Assertions.assertThat;

import ai.cerbur.crag.retrieval.result.DenseSearchResult;
import ai.cerbur.crag.retrieval.result.RrfFusionResult;
import ai.cerbur.crag.retrieval.result.SparseSearchResult;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * RrfFusionService 单元测试 —— 验证 child chunk 维度的 RRF 分数计算、跨流累加和 topN 截断.
 *
 * <p>RRF 阶段不回表 parent、不做 parent 去重；相邻 child 扩展交由 RetrievalService 处理.
 *
 * @since 2026-06-15
 */
@DisplayName("RrfFusionService RRF 融合服务")
class RrfFusionServiceTest {

  /** RRF 常数 k=60，rank 从 1 开始. */
  private static final int RRF_K = 60;

  /** 默认 topN. */
  private static final int TOP_N = 10;

  private final RrfFusionService rrfFusionService = new RrfFusionService();

  private SparseSearchResult sparse(
      long chunkId, long parentChunkId, double score, String content) {
    return new SparseSearchResult(chunkId, parentChunkId, score, content);
  }

  private DenseSearchResult dense(long chunkId, long parentChunkId, double score, String content) {
    return new DenseSearchResult(chunkId, parentChunkId, score, content);
  }

  @Nested
  @DisplayName("空输入保护")
  class EmptyInputProtection {

    @Test
    @DisplayName("两路均为空 → 返回空列表")
    void bothEmptyReturnsEmpty() {
      List<RrfFusionResult> results =
          rrfFusionService.fuse(Collections.emptyList(), Collections.emptyList(), TOP_N);

      assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("topN 非正数 → 返回空列表")
    void nonPositiveTopNReturnsEmpty() {
      List<RrfFusionResult> results =
          rrfFusionService.fuse(
              List.of(sparse(1001L, 100L, 0.90, "子内容")), Collections.emptyList(), 0);

      assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("sparse 为空、dense 有效 → 返回 child chunk")
    void sparseEmptyDenseValidReturnsChildChunk() {
      List<DenseSearchResult> denseList =
          List.of(new DenseSearchResult(1001L, 100L, 4, 0.95, "子内容一"));

      List<RrfFusionResult> results =
          rrfFusionService.fuse(Collections.emptyList(), denseList, TOP_N);

      assertThat(results).hasSize(1);
      assertThat(results.get(0).getChunkId()).isEqualTo(1001L);
      assertThat(results.get(0).getParentChunkId()).isEqualTo(100L);
      assertThat(results.get(0).getChunkIndex()).isEqualTo(4);
      assertThat(results.get(0).getContent()).isEqualTo("子内容一");
      assertThat(results.get(0).getBestDenseScore()).isEqualTo(0.95);
    }
  }

  @Nested
  @DisplayName("RRF 分数计算")
  class RrfScoreCalculation {

    @Test
    @DisplayName("单路单结果 → RRF = 1/(60+1)")
    void singleStreamSingleResultCorrectScore() {
      List<SparseSearchResult> sparseList = List.of(sparse(1001L, 100L, 0.99, "内容"));

      List<RrfFusionResult> results =
          rrfFusionService.fuse(sparseList, Collections.emptyList(), TOP_N);

      assertThat(results).hasSize(1);
      assertThat(results.get(0).getChunkId()).isEqualTo(1001L);
      assertThat(results.get(0).getRrfScore()).isEqualTo(1.0 / (RRF_K + 1));
    }

    @Test
    @DisplayName("单路多结果 → 排名越靠前 RRF 分数越高")
    void higherRankGetsHigherScore() {
      List<SparseSearchResult> sparseList =
          List.of(sparse(1001L, 100L, 0.90, "A"), sparse(1002L, 100L, 0.80, "B"));

      List<RrfFusionResult> results =
          rrfFusionService.fuse(sparseList, Collections.emptyList(), TOP_N);

      assertThat(results).hasSize(2);
      assertThat(results.get(0).getChunkId()).isEqualTo(1001L);
      assertThat(results.get(1).getChunkId()).isEqualTo(1002L);
      assertThat(results.get(0).getRrfScore()).isGreaterThan(results.get(1).getRrfScore());
    }

    @Test
    @DisplayName("同一 child 在两路都出现 → RRF 分数累加")
    void sameChildInBothStreamsAccumulatesScore() {
      List<SparseSearchResult> sparseList = List.of(sparse(1001L, 100L, 0.90, "重叠"));
      List<DenseSearchResult> denseList = List.of(dense(1001L, 100L, 0.95, "重叠"));

      List<RrfFusionResult> results = rrfFusionService.fuse(sparseList, denseList, TOP_N);

      assertThat(results).hasSize(1);
      assertThat(results.get(0).getChunkId()).isEqualTo(1001L);
      assertThat(results.get(0).getRrfScore()).isEqualTo(2.0 / (RRF_K + 1));
      assertThat(results.get(0).getBestSparseScore()).isEqualTo(0.90);
      assertThat(results.get(0).getBestDenseScore()).isEqualTo(0.95);
    }

    @Test
    @DisplayName("同一 child 在两路不同排名 → 累加不同 RRF 值")
    void sameChildDifferentRanksAccumulatesDifferentScores() {
      List<SparseSearchResult> sparseList = List.of(sparse(1001L, 100L, 0.90, "不同排名"));
      List<DenseSearchResult> denseList =
          List.of(
              dense(8001L, 800L, 0.99, "其他1"),
              dense(9001L, 900L, 0.98, "其他2"),
              dense(1001L, 100L, 0.85, "不同排名"));

      List<RrfFusionResult> results = rrfFusionService.fuse(sparseList, denseList, TOP_N);

      assertThat(results.get(0).getChunkId()).isEqualTo(1001L);
      double expectedScore = 1.0 / (RRF_K + 1) + 1.0 / (RRF_K + 3);
      assertThat(results.get(0).getRrfScore()).isEqualTo(expectedScore);
    }
  }

  @Nested
  @DisplayName("Child 维度保留")
  class ChildGranularity {

    @Test
    @DisplayName("同 parent 下多个 child 命中 → 保留多个 child，不做 parent 去重")
    void multipleChildrenUnderSameParentAreKept() {
      List<SparseSearchResult> sparseList =
          List.of(sparse(1001L, 100L, 0.90, "子1"), sparse(1002L, 100L, 0.80, "子2"));

      List<RrfFusionResult> results =
          rrfFusionService.fuse(sparseList, Collections.emptyList(), TOP_N);

      assertThat(results).hasSize(2);
      assertThat(results).extracting(RrfFusionResult::getChunkId).containsExactly(1001L, 1002L);
    }

    @Test
    @DisplayName("结果使用 child 内容，不回表 parent 内容")
    void resultUsesChildContent() {
      List<SparseSearchResult> sparseList = List.of(sparse(1001L, 100L, 0.90, "child text"));

      List<RrfFusionResult> results =
          rrfFusionService.fuse(sparseList, Collections.emptyList(), TOP_N);

      assertThat(results).hasSize(1);
      assertThat(results.get(0).getChunkId()).isEqualTo(1001L);
      assertThat(results.get(0).getContent()).isEqualTo("child text");
    }
  }

  @Nested
  @DisplayName("排序与截断")
  class SortingAndLimit {

    @Test
    @DisplayName("结果按 RRF 分数降序排列")
    void resultsSortedByRrfScoreDescending() {
      List<SparseSearchResult> sparseList =
          List.of(
              sparse(1001L, 100L, 0.90, "A"),
              sparse(1002L, 200L, 0.80, "B"),
              sparse(1003L, 300L, 0.70, "C"));

      List<RrfFusionResult> results =
          rrfFusionService.fuse(sparseList, Collections.emptyList(), TOP_N);

      assertThat(results)
          .extracting(RrfFusionResult::getChunkId)
          .containsExactly(1001L, 1002L, 1003L);
    }

    @Test
    @DisplayName("topN 截断生效")
    void topNLimitApplied() {
      List<SparseSearchResult> sparseList =
          List.of(
              sparse(1001L, 100L, 0.90, "A"),
              sparse(1002L, 200L, 0.80, "B"),
              sparse(1003L, 300L, 0.70, "C"));

      List<RrfFusionResult> results = rrfFusionService.fuse(sparseList, Collections.emptyList(), 2);

      assertThat(results).hasSize(2);
      assertThat(results).extracting(RrfFusionResult::getChunkId).containsExactly(1001L, 1002L);
    }
  }
}
