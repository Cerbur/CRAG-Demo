package ai.cerbur.crag.retrieval.service;

import ai.cerbur.crag.retrieval.bo.ChunkBO;
import ai.cerbur.crag.retrieval.dense.DenseQueryService;
import ai.cerbur.crag.retrieval.embedding.EmbeddingClient;
import ai.cerbur.crag.retrieval.rerank.RerankService;
import ai.cerbur.crag.retrieval.result.ChunkSearchResult;
import ai.cerbur.crag.retrieval.result.DenseSearchResult;
import ai.cerbur.crag.retrieval.result.RrfFusionResult;
import ai.cerbur.crag.retrieval.result.SparseSearchResult;
import ai.cerbur.crag.retrieval.rrf.RrfFusionService;
import ai.cerbur.crag.retrieval.sparse.SparseQueryService;
import ai.cerbur.crag.storage.ChunkDao;
import ai.cerbur.crag.storage.entity.Chunk;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 检索编排服务 —— 打包混合检索全链路（Embed → Sparse + Dense → RRF → 邻接扩展 → Rerank）.
 *
 * <p>检索管道类型流：
 *
 * <pre>
 *   SparseSearchResult + DenseSearchResult    (两路并行，各自最内层窄类型)
 *   → RrfFusionResult                         (RRF 融合，中间层类型)
 *   → ChunkSearchResult                       (Rerank 组装，最外层宽类型，四路得分齐全)
 * </pre>
 *
 * 对外暴露单一入口，下游（如 crag-query 的 UserQueryService）调用 retrieve() 拿到检索结果后直接接 LLM. Rerank
 * 作为检索模块内部细节在此处完成，外部不感知.
 *
 * @since 2026-06-15
 */
@Service
public class RetrievalService {

  private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);

  @Autowired private EmbeddingClient embeddingClient;

  @Autowired private SparseQueryService sparseQueryService;

  @Autowired private DenseQueryService denseQueryService;

  @Autowired private RrfFusionService rrfFusionService;

  @Autowired private RerankService rerankService;

  @Autowired private ChunkDao chunkDao;

  /**
   * 执行混合检索全链路，返回 Rerank 重排后的检索结果列表.
   *
   * <p>流水线步骤： 1. Query Embedding 向量化 2. Sparse FTS + Dense 向量并行检索（topK = topN × 3），各自返回窄类型 3. RRF
   * 融合 → child chunk 维度的 RrfFusionResult 4. 取 top RRF child 及其相邻 child，送入 Rerank 语义重排序 5. 返回 Rerank
   * 后的 ChunkSearchResult，不向查询链路透传 JPA Entity 6. 输出最终结果日志（含 sparse/dense/rrf/rerank 四路得分）
   *
   * @param query 用户问题文本
   * @param topN 最终返回的 chunk 数量
   * @return 按 Rerank 分数降序排列的检索结果列表
   */
  public List<ChunkSearchResult> retrieve(String query, int topN) {
    if (query == null || query.isBlank() || topN <= 0) {
      return Collections.emptyList();
    }

    // Step 1: Query embedding
    float[] queryEmbedding = embeddingClient.embed(query);

    // Step 2: Parallel search — Sparse FTS + Dense vector (narrow types)
    int topK = topN * 3;
    List<SparseSearchResult> sparseResults = sparseQueryService.search(query, topK);
    List<DenseSearchResult> denseResults = denseQueryService.search(queryEmbedding, topK);

    log.info("Retrieval search — sparse={}, dense={}", sparseResults.size(), denseResults.size());

    // Step 3: RRF fusion stays at child chunk granularity.
    List<RrfFusionResult> fusedResults = rrfFusionService.fuse(sparseResults, denseResults, topN);

    if (fusedResults.isEmpty()) {
      log.info("RRF fusion returned no results");
      return Collections.emptyList();
    }
    log.info("RRF fusion — {} child chunks", fusedResults.size());

    // Step 4: Rerank top RRF child chunks plus adjacent child chunks in the same parent window.
    List<RrfFusionResult> rerankCandidates = expandRerankCandidates(fusedResults);
    List<ChunkSearchResult> rerankedResults = rerankService.rerank(query, rerankCandidates);

    // Step 5: Keep only final topN result objects. Do not expose storage entities to query chain.
    List<ChunkSearchResult> topResults = rerankedResults.stream().limit(topN).toList();

    // Step 6: Log final results with all four scores for analysis
    log.info("Retrieval complete — {} chunks returned", topResults.size());
    logScores(topResults);

    return topResults;
  }

  /**
   * 将 top RRF child 扩展为 rerank 候选：命中 child + 同 parent 下前后相邻 child.
   *
   * @param fusedResults child 维度 RRF 结果
   * @return 去重后的 rerank 候选列表
   */
  private List<RrfFusionResult> expandRerankCandidates(List<RrfFusionResult> fusedResults) {
    Map<String, RrfFusionResult> candidates = new LinkedHashMap<>();
    Set<ParentIndexKey> adjacentKeys = new LinkedHashSet<>();

    for (RrfFusionResult fused : fusedResults) {
      candidates.put(fused.getChunkId(), fused);

      if (fused.getParentChunkId() == null
          || fused.getParentChunkId().isBlank()
          || fused.getChunkIndex() == null) {
        continue;
      }

      adjacentKeys.add(new ParentIndexKey(fused.getParentChunkId(), fused.getChunkIndex() - 1));
      adjacentKeys.add(new ParentIndexKey(fused.getParentChunkId(), fused.getChunkIndex() + 1));
    }

    for (Chunk adjacent : findAdjacentChunks(adjacentKeys)) {
      candidates.putIfAbsent(
          adjacent.getChunkId(), new RrfFusionResult(toChunkBO(adjacent), 0.0, null, null));
    }

    return new ArrayList<>(candidates.values());
  }

  /** 一次性查询所有相邻 child，并过滤掉 parent/index 交叉命中的多余行. */
  private List<Chunk> findAdjacentChunks(Set<ParentIndexKey> adjacentKeys) {
    if (adjacentKeys.isEmpty()) {
      return Collections.emptyList();
    }
    List<String> parentChunkIds =
        adjacentKeys.stream().map(ParentIndexKey::parentChunkId).distinct().toList();
    List<Integer> chunkIndexes =
        adjacentKeys.stream().map(ParentIndexKey::chunkIndex).distinct().toList();

    return chunkDao.findByParentChunkIdsAndChunkIndexes(parentChunkIds, chunkIndexes).stream()
        .filter(
            chunk ->
                adjacentKeys.contains(
                    new ParentIndexKey(chunk.getParentChunkId(), chunk.getChunkIndex())))
        .collect(Collectors.toList());
  }

  /** 将持久化 Entity 转为 retrieval 业务对象，避免 Entity 进入查询业务链路. */
  private static ChunkBO toChunkBO(Chunk chunk) {
    return new ChunkBO(
        chunk.getChunkId(), chunk.getParentChunkId(), chunk.getChunkIndex(), chunk.getContent());
  }

  /**
   * 以 INFO 级别输出最终检索结果的全部分数，方便分析.
   *
   * <p>每一条输出格式：chunk=<chunkId> sparse=<score> dense=<score> rrf=<score> rerank=<score> 未参与该阶段的得分显示为
   * "-".
   */
  private void logScores(List<ChunkSearchResult> results) {
    for (int i = 0; i < results.size(); i++) {
      ChunkSearchResult r = results.get(i);
      log.info(
          "  [{}] chunk={} sparse={} dense={} rrf={} rerank={}",
          i,
          r.getChunkId(),
          fmt(r.getSparseScore()),
          fmt(r.getDenseScore()),
          fmt(r.getRrfScore()),
          fmt(r.getRerankScore()));
    }
  }

  /** 格式化得分：null → "-"，否则保留 4 位小数. */
  private static String fmt(Double score) {
    if (score == null) {
      return "-";
    }
    return String.format("%.4f", score);
  }

  /** parent + child index 组成的相邻 child 定位键. */
  private record ParentIndexKey(String parentChunkId, Integer chunkIndex) {}
}
