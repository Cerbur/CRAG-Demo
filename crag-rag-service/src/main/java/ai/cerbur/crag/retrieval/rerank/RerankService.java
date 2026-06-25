package ai.cerbur.crag.retrieval.rerank;

import ai.cerbur.crag.retrieval.api.result.ChunkSearchResult;
import ai.cerbur.crag.retrieval.rerank.client.RerankClient;
import ai.cerbur.crag.retrieval.rerank.client.RerankClient.RerankResult;
import ai.cerbur.crag.retrieval.result.RrfFusionResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 重排序服务 —— 消费 {@link RrfFusionResult} 列表，委托 RerankClient 做语义重排序， 通过 {@link
 * ChunkSearchResult#fromRrfWithRerank(RrfFusionResult, double)} 组装最终宽类型.
 *
 * <p>Rerank 是检索模块内部细节，在 RetrievalService 内部完成， 外部模块（如 crag-query）不需要感知此步骤.
 *
 * @since 2026-06-10
 */
@Component
public class RerankService {

  private static final Logger log = LoggerFactory.getLogger(RerankService.class);

  @Autowired private RerankClient rerankClient;

  /**
   * 对 RRF 融合后的 child chunk 候选列表做语义重排序.
   *
   * <p>提取每个 chunk 的内容文本，委托 RerankClient 获取 rerank 分数， 通过 {@link
   * ChunkSearchResult#fromRrfWithRerank(RrfFusionResult, double)} 组装包含全部四路得分的最终宽类型，按 rerank score
   * 降序排列.
   *
   * @param query 用户问题
   * @param chunks RRF 融合后的 child chunk 候选列表
   * @return 按 rerank score 降序排列的 ChunkSearchResult 列表（四路得分齐全）
   */
  public List<ChunkSearchResult> rerank(String query, List<RrfFusionResult> chunks) {
    if (query == null || query.isBlank() || chunks == null || chunks.isEmpty()) {
      return Collections.emptyList();
    }

    // Extract document texts for rerank
    List<String> documents = chunks.stream().map(RrfFusionResult::getContent).toList();

    // Call sidecar
    List<RerankResult> rerankResults = rerankClient.rerank(query, documents);

    if (rerankResults.isEmpty()) {
      log.warn("Rerank returned empty results, returning original order");
      // Fallback: assemble without rerankScore but preserve all upstream scores
      List<ChunkSearchResult> fallback = new ArrayList<>(chunks.size());
      for (RrfFusionResult rrf : chunks) {
        fallback.add(ChunkSearchResult.fromRrfWithRerank(rrf, 0.0));
      }
      return fallback;
    }

    // Build index → score map
    float[] scores = new float[chunks.size()];
    for (RerankResult r : rerankResults) {
      if (r.index() >= 0 && r.index() < scores.length) {
        scores[r.index()] = r.score();
      }
    }

    // Build (originalIndex, rrf) pairs with rerank score, sort by rerank score descending
    List<RrfWithRerank> indexed = new ArrayList<>(chunks.size());
    for (int i = 0; i < chunks.size(); i++) {
      indexed.add(new RrfWithRerank(i, chunks.get(i), scores[i]));
    }

    indexed.sort((a, b) -> Float.compare(b.rerankScore, a.rerankScore));

    // Assemble final ChunkSearchResult from each RrfFusionResult + rerankScore
    List<ChunkSearchResult> reordered = new ArrayList<>(indexed.size());
    for (RrfWithRerank item : indexed) {
      reordered.add(ChunkSearchResult.fromRrfWithRerank(item.rrf, item.rerankScore));
    }

    log.info("Rerank complete — {} chunks reordered", reordered.size());
    if (log.isDebugEnabled()) {
      for (int i = 0; i < Math.min(reordered.size(), 10); i++) {
        ChunkSearchResult r = reordered.get(i);
        log.debug(
            "  rerank[{}] chunk={} sparse={} dense={} rrf={} rerank={}",
            i,
            r.getChunkId(),
            r.getSparseScore(),
            r.getDenseScore(),
            r.getRrfScore(),
            r.getRerankScore());
      }
    }

    return reordered;
  }

  /** 带原始索引与 rerank 分数的 RRF 结果包装，用于 rerank 后重排序. */
  private record RrfWithRerank(int originalIndex, RrfFusionResult rrf, float rerankScore) {}
}
