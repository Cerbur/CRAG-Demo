package ai.cerbur.crag.retrieval.rrf;

import ai.cerbur.crag.retrieval.bo.ChunkBO;
import ai.cerbur.crag.retrieval.result.DenseSearchResult;
import ai.cerbur.crag.retrieval.result.RrfFusionResult;
import ai.cerbur.crag.retrieval.result.SparseSearchResult;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * RRF 融合服务 —— 消费 {@link SparseSearchResult} + {@link DenseSearchResult} 两路窄类型， 在 child chunk 维度做
 * Reciprocal Rank Fusion，产出 {@link RrfFusionResult}.
 *
 * <p>RRF 只负责召回结果融合，不在此处回表 parent 或按 parent 去重。 下游 rerank 候选由 top RRF child 及其同 parent 下相邻 child 组成.
 *
 * @since 2026-06-10
 */
@Component
public class RrfFusionService {

  /** RRF 常数 k，防止单路 rank=1 导致分母过小. */
  private static final int RRF_K = 60;

  /**
   * 对两路 child chunk 结果执行 RRF 融合.
   *
   * <p>融合步骤： 1. 对每路结果按排名计算 {@code 1 / (k + rank)}（rank 从 1 开始）. 2. 同一 child 在多路出现时累加 RRF 分数. 3. 保留
   * child 在 Sparse / Dense 两路的原始得分. 4. 按融合分数降序排列，取 topN.
   *
   * @param sparseResults Sparse 检索结果（child chunk 维度）
   * @param denseResults Dense 检索结果（child chunk 维度）
   * @param topN 融合后保留数量
   * @return RRF 融合后的 child chunk 结果列表，按 RRF 分数降序
   */
  public List<RrfFusionResult> fuse(
      List<SparseSearchResult> sparseResults, List<DenseSearchResult> denseResults, int topN) {
    if (topN <= 0) {
      return Collections.emptyList();
    }

    Map<Long, Double> childRrfScores = new LinkedHashMap<>();
    Map<Long, Double> childSparseScores =
        sparseResults.stream()
            .collect(
                Collectors.toMap(
                    SparseSearchResult::getChunkId,
                    SparseSearchResult::getSparseScore,
                    Math::max,
                    LinkedHashMap::new));
    Map<Long, Double> childDenseScores =
        denseResults.stream()
            .collect(
                Collectors.toMap(
                    DenseSearchResult::getChunkId,
                    DenseSearchResult::getDenseScore,
                    Math::max,
                    LinkedHashMap::new));
    Map<Long, ChunkBO> childChunks = new LinkedHashMap<>();

    for (SparseSearchResult result : sparseResults) {
      childChunks.putIfAbsent(result.getChunkId(), result.getChunk());
    }
    for (DenseSearchResult result : denseResults) {
      childChunks.putIfAbsent(result.getChunkId(), result.getChunk());
    }

    accumulate(childRrfScores, sparseResults, SparseSearchResult::getChunkId);
    accumulate(childRrfScores, denseResults, DenseSearchResult::getChunkId);

    if (childRrfScores.isEmpty()) {
      return Collections.emptyList();
    }

    return childRrfScores.entrySet().stream()
        .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
        .limit(topN)
        .map(
            entry ->
                new RrfFusionResult(
                    childChunks.get(entry.getKey()),
                    entry.getValue(),
                    childSparseScores.get(entry.getKey()),
                    childDenseScores.get(entry.getKey())))
        .toList();
  }

  /**
   * 对一路检索结果计算 RRF 分数并累加.
   *
   * @param <T> 检索结果类型（SparseSearchResult 或 DenseSearchResult）
   * @param childRrfScores childChunkId → 累计 RRF 分数
   * @param results 该路检索结果列表（已按原始分数降序）
   * @param chunkIdFn 从 T 提取 chunkId
   */
  private <T> void accumulate(
      Map<Long, Double> childRrfScores, List<T> results, Function<T, Long> chunkIdFn) {
    for (int i = 0; i < results.size(); i++) {
      int rank = i + 1; // 1-based
      double rrfScore = 1.0 / (RRF_K + rank);
      childRrfScores.merge(chunkIdFn.apply(results.get(i)), rrfScore, Double::sum);
    }
  }
}
