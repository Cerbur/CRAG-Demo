package ai.cerbur.crag.retrieval.api;

import ai.cerbur.crag.retrieval.api.embedding.EmbeddingClient;
import ai.cerbur.crag.retrieval.api.result.ChunkSearchResult;
import ai.cerbur.crag.retrieval.api.result.ParentEvidenceResult;
import ai.cerbur.crag.retrieval.bo.ChunkBO;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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

  // ============================================================
  // Public API
  // ============================================================

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
    int recallN = saturatingMultiply(topN, 3);
    PipelineConfig config = new PipelineConfig(recallN, topN, topN);
    InternalRetrievalResult internal = retrieveInternal(query, config);

    List<ChunkSearchResult> topResults = internal.rerankedChildren().stream().limit(topN).toList();

    log.info("Retrieval complete — {} chunks returned", topResults.size());
    logScores(topResults);

    return topResults;
  }

  /**
   * 执行 parent evidence 检索 —— 返回包含完整 parent 内容的证据列表.
   *
   * <p>与 {@link #retrieve(String, int)} 的区别：
   *
   * <ul>
   *   <li>内部使用召回 3N、RRF 3N、最终 3N 的候选倍率；
   *   <li>从 Rerank 后前 3N 个 child 聚合 parent 候选；
   *   <li>批量回表读取完整 parent 内容；
   *   <li>跳过内容缺失/null/blank 的 parent 并以后续有效候选补位；
   *   <li>最终截取前 {@code topN} 个有效 parent。
   * </ul>
   *
   * <p>返回的 {@link ParentEvidenceResult} 不携带检索分数，不暴露 Entity 或 DAO 类型.
   *
   * @param query 用户问题文本
   * @param topN 最终最多返回的不同 parent 数量
   * @return 按 Evidence 排名升序排列的 parent evidence 列表
   */
  public List<ParentEvidenceResult> retrieveEvidence(String query, int topN) {
    if (query == null || query.isBlank() || topN <= 0) {
      return Collections.emptyList();
    }

    int limit = saturatingMultiply(topN, 3);
    PipelineConfig config = new PipelineConfig(limit, limit, limit);
    InternalRetrievalResult internal = retrieveInternal(query, config);

    if (internal.rerankedChildren().isEmpty()) {
      return Collections.emptyList();
    }

    // Aggregate evidence candidates from 3N child window
    List<EvidenceCandidate> candidates =
        aggregateEvidenceCandidates(
            internal.rerankedChildren(), internal.realRrfHitChunkIds(), limit);

    if (candidates.isEmpty()) {
      return Collections.emptyList();
    }

    // Batch read parent content — no N+1, no order dependency
    List<String> parentIds = candidates.stream().map(EvidenceCandidate::parentChunkId).toList();
    List<ParentChunkContent> parentContents = chunkDao.findParentContentsByIds(parentIds);

    // Build content map (first occurrence wins for duplicate projections)
    Map<String, String> contentMap = new LinkedHashMap<>();
    for (ParentChunkContent pc : parentContents) {
      if (contentMap.containsKey(pc.chunkId())) {
        log.warn("Duplicate parent projection for chunkId={}, keeping first", pc.chunkId());
      } else {
        contentMap.put(pc.chunkId(), pc.content());
      }
    }

    // Assemble results, skip missing/null/blank content, fill from subsequent candidates
    List<ParentEvidenceResult> results = new ArrayList<>();
    int invalidCount = 0;

    for (EvidenceCandidate candidate : candidates) {
      String content = contentMap.get(candidate.parentChunkId());

      if (content == null || content.isBlank()) {
        invalidCount++;
        logWarnInvalidParent(candidate, content == null ? "null" : "blank");
        continue;
      }

      results.add(
          new ParentEvidenceResult(
              candidate.parentChunkId(), content, candidate.matchedChildIds()));
    }

    if (invalidCount > 0) {
      log.warn(
          "Evidence retrieval — {} parent(s) skipped due to missing or blank content",
          invalidCount);
    }

    if (results.isEmpty()) {
      return Collections.emptyList();
    }

    // Truncate to topN
    int end = Math.min(results.size(), topN);
    return Collections.unmodifiableList(results.subList(0, end));
  }

  /**
   * Log a single invalid parent warning — includes parentChunkId, hit count, and up to 10 child
   * IDs.
   */
  private void logWarnInvalidParent(EvidenceCandidate candidate, String reason) {
    if (!log.isWarnEnabled()) {
      return;
    }
    List<String> childIds = candidate.matchedChildIds();
    int showCount = Math.min(childIds.size(), 10);
    List<String> preview = childIds.subList(0, showCount);
    log.warn(
        "Parent evidence skipped — parentChunkId={} has {} matched child(ren), "
            + "content is {}, first {} child IDs: {}",
        candidate.parentChunkId(),
        childIds.size(),
        reason,
        showCount,
        preview);
  }

  // ============================================================
  // Internal retrieval pipeline
  // ============================================================

  /**
   * 内部检索全链路 —— 支持可配置的召回/RRF/最终 child 三限额.
   *
   * <p>返回 {@link InternalRetrievalResult}，同时包含 Rerank 后的完整 child 列表和真实 RRF 命中 child ID 的有序集合，供
   * Evidence 候选聚合使用.
   */
  InternalRetrievalResult retrieveInternal(String query, PipelineConfig config) {
    // Step 1: Query embedding
    float[] queryEmbedding = embeddingClient.embed(query);

    // Step 2: Parallel search — Sparse FTS + Dense vector (narrow types)
    List<SparseSearchResult> sparseResults = sparseQueryService.search(query, config.recallN);
    List<DenseSearchResult> denseResults = denseQueryService.search(queryEmbedding, config.recallN);

    log.info("Retrieval search — sparse={}, dense={}", sparseResults.size(), denseResults.size());

    // Step 3: RRF fusion stays at child chunk granularity.
    List<RrfFusionResult> fusedResults =
        rrfFusionService.fuse(sparseResults, denseResults, config.rrfN);

    if (fusedResults.isEmpty()) {
      log.info("RRF fusion returned no results");
      return new InternalRetrievalResult(Collections.emptyList(), new LinkedHashSet<>());
    }
    log.info("RRF fusion — {} child chunks", fusedResults.size());

    // Step 4: Rerank top RRF child chunks plus adjacent child chunks. Track real RRF hit IDs.
    RerankCandidateSet candidateSet = prepareRerankCandidates(fusedResults);
    List<ChunkSearchResult> rerankedResults =
        rerankService.rerank(query, candidateSet.allCandidates());

    return new InternalRetrievalResult(rerankedResults, candidateSet.rrfHitChunkIds());
  }

  // ============================================================
  // Rerank candidate preparation with RRF hit tracking
  // ============================================================

  /**
   * 将 top RRF child 扩展为 rerank 候选并显式跟踪真实 RRF 命中.
   *
   * <p>返回 {@link RerankCandidateSet}，区分真实 RRF 命中 child 与相邻扩展 child. 禁止通过分数是否为 null 或零反推来源.
   */
  RerankCandidateSet prepareRerankCandidates(List<RrfFusionResult> fusedResults) {
    LinkedHashSet<String> rrfHitIds = new LinkedHashSet<>();
    Map<String, RrfFusionResult> candidates = new LinkedHashMap<>();
    Set<ParentIndexKey> adjacentKeys = new LinkedHashSet<>();

    for (RrfFusionResult fused : fusedResults) {
      candidates.put(fused.getChunkId(), fused);
      rrfHitIds.add(fused.getChunkId());

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

    return new RerankCandidateSet(new ArrayList<>(candidates.values()), rrfHitIds);
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

  // ============================================================
  // Evidence candidate aggregation
  // ============================================================

  /**
   * 从 Rerank 后的 child 列表聚合 parent evidence 候选.
   *
   * <p>在 Evidence 候选窗口（rerankedChildren 的前 windowN 个）内：
   *
   * <ul>
   *   <li>按 parentChunkId 分组；
   *   <li>parent 排名由窗口内该 parent 的最高 Rerank 名次确定（相邻扩展 child 可提升排名）；
   *   <li>matchedChildIds 只包含窗口内的真实 RRF 命中 child，按 Rerank 顺序稳定去重；
   *   <li>只有相邻扩展、没有任何真实命中 child 的 parent 不返回；
   *   <li>真实命中 child 被主动截断而只剩相邻 child 的 parent 同样丢弃。
   * </ul>
   *
   * @param rerankedChildren Rerank 后的完整 child 列表
   * @param realRrfHitChunkIds 真实 RRF 命中 child ID 的有序集合
   * @param windowN Evidence 候选窗口大小
   * @return 按 parent 排名升序排列的 Evidence 候选列表
   */
  static List<EvidenceCandidate> aggregateEvidenceCandidates(
      List<ChunkSearchResult> rerankedChildren, Set<String> realRrfHitChunkIds, int windowN) {

    if (rerankedChildren == null || rerankedChildren.isEmpty()) {
      return Collections.emptyList();
    }

    List<ChunkSearchResult> window = rerankedChildren.stream().limit(windowN).toList();

    // Maintain insertion order for stable results
    Map<String, Integer> parentBestRank = new LinkedHashMap<>();
    Map<String, LinkedHashSet<String>> parentMatchedChildren = new LinkedHashMap<>();

    for (int i = 0; i < window.size(); i++) {
      ChunkSearchResult child = window.get(i);
      String pid = child.getParentChunkId();

      // Skip children without a valid parent — do not treat child as its own parent
      if (pid == null || pid.isBlank()) {
        continue;
      }

      // Record best rank for this parent (first occurrence = best rank)
      parentBestRank.putIfAbsent(pid, i);

      // Only real RRF hits (not adjacent expansion) enter matchedChildIds
      if (realRrfHitChunkIds.contains(child.getChunkId())) {
        parentMatchedChildren
            .computeIfAbsent(pid, k -> new LinkedHashSet<>())
            .add(child.getChunkId());
      }
    }

    // Build candidates: only parents with at least one real RRF hit child in the window
    List<EvidenceCandidate> candidates = new ArrayList<>();
    for (Map.Entry<String, LinkedHashSet<String>> entry : parentMatchedChildren.entrySet()) {
      String pid = entry.getKey();
      int rank = parentBestRank.get(pid);
      candidates.add(new EvidenceCandidate(pid, List.copyOf(entry.getValue()), rank));
    }

    // Sort by best rank ascending
    candidates.sort(Comparator.comparingInt(EvidenceCandidate::bestRank));

    return Collections.unmodifiableList(candidates);
  }

  // ============================================================
  // Utility
  // ============================================================

  /** 将持久化 Entity 转为 retrieval 业务对象，避免 Entity 进入查询业务链路. */
  private static ChunkBO toChunkBO(Chunk chunk) {
    return new ChunkBO(
        chunk.getChunkId(), chunk.getParentChunkId(), chunk.getChunkIndex(), chunk.getContent());
  }

  /**
   * 饱和乘法 —— 防止整数溢出，结果上限为 {@link Integer#MAX_VALUE}.
   *
   * <p>用于 Evidence 路径的三倍候选扩展，不额外新增任意业务上限.
   */
  static int saturatingMultiply(int a, int b) {
    long result = (long) a * (long) b;
    return (result > Integer.MAX_VALUE) ? Integer.MAX_VALUE : (int) result;
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

  // ============================================================
  // Internal records
  // ============================================================

  /** 检索管道内部三限额配置. */
  record PipelineConfig(int recallN, int rrfN, int finalN) {
    PipelineConfig {
      if (recallN <= 0 || rrfN <= 0 || finalN <= 0) {
        throw new IllegalArgumentException("limits must be positive");
      }
    }
  }

  /**
   * 内部检索结果 —— 同时携带 Rerank 后的 child 列表和真实 RRF 命中 child ID 的有序集合.
   *
   * <p>真实 RRF 命中 child 通过 {@link LinkedHashSet} 按 RRF 融合顺序维护， 禁止通过分数是否为 null 或零反推来源.
   */
  record InternalRetrievalResult(
      List<ChunkSearchResult> rerankedChildren, LinkedHashSet<String> realRrfHitChunkIds) {}

  /** Rerank 候选集 —— 包含所有候选和真实 RRF 命中 child ID 的有序集合. */
  record RerankCandidateSet(
      List<RrfFusionResult> allCandidates, LinkedHashSet<String> rrfHitChunkIds) {}

  /**
   * Evidence 候选 —— 聚合后的 parent 维度中间结果.
   *
   * <p>bestRank 为该 parent 在 Evidence 候选窗口内的最高 Rerank 名次（0 起始）， 相邻扩展 child 可影响 parent 排名，但不进入
   * matchedChildIds.
   */
  record EvidenceCandidate(String parentChunkId, List<String> matchedChildIds, int bestRank) {}

  /** parent + child index 组成的相邻 child 定位键. */
  private record ParentIndexKey(String parentChunkId, Integer chunkIndex) {}
}
