package ai.cerbur.crag.smoke.controller;

import ai.cerbur.crag.common.dto.result.Response;
import ai.cerbur.crag.id.api.CragIdGenerator;
import ai.cerbur.crag.id.api.IdEntityType;
import ai.cerbur.crag.retrieval.api.RetrievalService;
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
import ai.cerbur.crag.storage.ChunkEmbeddingDao;
import ai.cerbur.crag.storage.ChunkFtsDao;
import ai.cerbur.crag.storage.entity.Chunk;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 冒烟测试接口 —— 仅在 {@code smoke} Profile 激活时启用，验证 HTTP 可达 + 数据库连通 + 检索全链路.
 *
 * <p>用于快速验证全链路基础设施是否就绪。所有端点统一受 {@link Profile @Profile("smoke")} 限制， 默认应用启动不暴露 {@code
 * /api/v1/smoke/test/**}。
 *
 * <p>本类是计划 {@code plan_9} 任务 9.5 从 {@code crag-app} 迁移到 {@code crag-smoke} 的唯一受控诊断例外。
 *
 * @since 2026-06-10
 */
@Profile("smoke")
@RestController
@RequestMapping("/api/v1/smoke/test")
public class TestController {

  /** Chunk 主表 DAO，用于验证 chunk 表可查询. */
  @Autowired private ChunkDao chunkDao;

  /** Chunk Embedding 表 DAO，用于验证 embedding 向量表可查询. */
  @Autowired private ChunkEmbeddingDao chunkEmbeddingDao;

  /** Chunk FTS 表 DAO，用于验证全文检索表可查询. */
  @Autowired private ChunkFtsDao chunkFtsDao;

  /** Embedding 客户端，用于将 query 文本转为向量. */
  @Autowired private EmbeddingClient embeddingClient;

  /** Sparse 查询服务，用于 FTS 关键词检索. */
  @Autowired private SparseQueryService sparseQueryService;

  /** Dense 查询服务，用于向量相似度检索. */
  @Autowired private DenseQueryService denseQueryService;

  /** RRF 融合服务，用于在 child chunk 维度合并 Sparse + Dense 结果. */
  @Autowired private RrfFusionService rrfFusionService;

  /** Rerank 服务，用于对 RRF 融合结果做语义重排序. */
  @Autowired private RerankService rerankService;

  /** 检索编排服务，打包混合检索全链路（Embed → Sparse + Dense → RRF → Rerank → Chunk 回表）. */
  @Autowired private RetrievalService retrievalService;

  /** Snowflake ID 发号器 —— 为测试 chunk 预生成唯一 long ID. */
  @Autowired private CragIdGenerator cragIdGenerator;

  /**
   * 冒烟测试 —— 查询三张表的记录数，验证 HTTP + JDBC 全链路连通.
   *
   * <p>三张表即使无数据也会返回 count=0，重点确认 JDBC 连接正常。 数据库不可达时 HikariCP 抛异常，由 GlobalExceptionHandler 统一转为 500
   * 错误响应.
   *
   * @return 统一响应，result 含 status / database / tables 三级信息
   */
  @GetMapping("/smoke")
  public Response<SmokeResponse> smoke() {
    long chunkCount = chunkDao.count();
    long embeddingCount = chunkEmbeddingDao.count();
    long ftsCount = chunkFtsDao.count();

    SmokeTableCounts tables = new SmokeTableCounts(chunkCount, embeddingCount, ftsCount);
    return Response.success(new SmokeResponse("ok", "connected", tables));
  }

  /**
   * 检索全链路冒烟测试 —— 委托 RetrievalService.retrieve(...) 执行完整门面链路.
   *
   * <p>覆盖 Embed → Sparse + Dense → RRF → 邻接扩展 → Rerank 全流程， 包括 plan 6.10 要求的"top RRF child + 同
   * parent 相邻 child 参与 rerank"逻辑. 中间阶段计数请使用 /api/v1/smoke/test/rrf 独立端点.
   *
   * @param query 用户查询文本
   * @param topN 最终返回数量，默认 10
   * @return Response 包装的 RetrievalSmokeResponse
   */
  @GetMapping("/retrieval")
  public Response<RetrievalSmokeResponse> retrieval(
      @RequestParam("query") String query,
      @RequestParam(value = "topN", defaultValue = "10") int topN) {
    // 委托 RetrievalService 全链路：Embed → Sparse + Dense → RRF → 邻接扩展 → Rerank
    List<ChunkSearchResult> results = retrievalService.retrieve(query, topN);
    return Response.success(new RetrievalSmokeResponse(query, results));
  }

  /**
   * Parent evidence 检索冒烟测试 —— 通过 retrieveEvidence 返回完整 parent 内容证据.
   *
   * <p>委托 RetrievalService.retrieveEvidence(...) 执行 child 召回 → RRF → Rerank → parent 聚合 → 批量回表全流程.
   * 返回的 {@link ParentEvidenceResult} 包含完整 parent 内容及真实 RRF 命中的 child ID 列表， 不携带检索分数. 此端点不改变既有
   * /retrieval child 诊断契约.
   *
   * @param query 用户查询文本
   * @param topN 最终最多返回的不同 parent 数量，默认 5
   * @return Response 包装的 List&lt;ParentEvidenceResult&gt;
   */
  @GetMapping("/retrieval/evidence")
  public Response<List<ParentEvidenceResult>> retrievalEvidence(
      @RequestParam("query") String query,
      @RequestParam(value = "topN", defaultValue = "5") int topN) {
    List<ParentEvidenceResult> results = retrievalService.retrieveEvidence(query, topN);
    return Response.success(results);
  }

  /**
   * 写入 chunk 冒烟测试 —— 创建 1 个 parent chunk + 1 个 child chunk 验证写入链路.
   *
   * <p>Child chunk 的 denseStatus/sparseStatus 初始为 INIT，交由 Cron 异步处理。 Parent chunk 的
   * denseStatus/sparseStatus 为 SKIPPED，不参与向量化/FTS。
   *
   * @param request 写入请求，含 title 和 content
   * @return Response 包装的 ChunkWriteResponse（含 docId + chunkId 列表供后续端点使用）
   */
  @PostMapping("/chunk")
  public Response<ChunkWriteResponse> writeChunk(@RequestBody ChunkWriteRequest request) {
    long docId = cragIdGenerator.nextId(IdEntityType.LEGACY_DOCUMENT);
    long parentId = cragIdGenerator.nextId(IdEntityType.CHUNK);
    Chunk parent =
        Chunk.createParent(parentId, docId, request.content(), request.content().length(), 0, null);
    long childId = cragIdGenerator.nextId(IdEntityType.CHUNK);
    Chunk child =
        Chunk.createChild(
            childId, docId, parentId, request.content(), request.content().length(), 0, null);
    List<Chunk> saved = chunkDao.saveAll(List.of(parent, child));
    List<Long> childIds =
        saved.stream()
            .filter(c -> c.getParentChunkId() != Chunk.NO_PARENT)
            .map(Chunk::getChunkId)
            .toList();
    List<Long> parentIds =
        saved.stream()
            .filter(c -> c.getParentChunkId() == Chunk.NO_PARENT)
            .map(Chunk::getChunkId)
            .toList();
    return Response.success(new ChunkWriteResponse(docId, childIds.size(), childIds, parentIds));
  }

  /**
   * chunk 状态检查端点 —— 按 chunkId 返回完整 Chunk 行，供 LLM 判断状态流转正确性.
   *
   * <p>返回 Chunk 实体的所有字段，包括 denseStatus、sparseStatus、version、createdAt、updatedAt 等。 若 chunkId 不存在则
   * result 为 null（success=true, code=0），由调用方自行处理.
   *
   * @param chunkId chunk ID
   * @return Chunk 实体
   */
  @GetMapping("/chunk/{chunkId}/status")
  public Response<Chunk> chunkStatus(@PathVariable long chunkId) {
    Chunk chunk = chunkDao.findByChunkId(chunkId);
    return Response.success(chunk);
  }

  /**
   * chunk 索引数据查询端点 —— 按 chunkId 检查 dense embedding 和 sparse FTS 索引是否已生成.
   *
   * <p>分别查询 chunk_embedding 表和 chunk_fts 表是否存在对应行， 同时返回 chunk 主表中的 denseStatus / sparseStatus
   * 供对照分析。 若 chunk 主表不存在，status 字段为 null.
   *
   * @param chunkId chunk ID
   * @return 索引存在性及 chunk 状态信息
   */
  @GetMapping("/chunk/{chunkId}/indexes")
  public Response<ChunkIndexesResponse> chunkIndexes(@PathVariable long chunkId) {
    boolean embeddingExists = chunkEmbeddingDao.existsByChunkId(chunkId);
    boolean ftsExists = chunkFtsDao.existsByChunkId(chunkId);
    Chunk chunk = chunkDao.findByChunkId(chunkId);
    String denseStatus = chunk != null ? chunk.getDenseStatus().name() : null;
    String sparseStatus = chunk != null ? chunk.getSparseStatus().name() : null;
    return Response.success(
        new ChunkIndexesResponse(chunkId, embeddingExists, ftsExists, denseStatus, sparseStatus));
  }

  /**
   * RRF 融合阶段冒烟测试 —— 验证 Embed → Sparse → Dense → RRF 融合（不包含 Rerank）.
   *
   * <p>独立运行 RRF 及之前的阶段，返回各阶段完整结果列表而非仅计数， 便于观察 RRF 融合前后的 child chunk score 变化.
   *
   * @param query 用户查询文本
   * @param topN 最终返回数量，默认 10
   * @return RrfSmokeResponse 含 sparse/dense 计数及 RRF 融合后完整结果
   */
  @GetMapping("/rrf")
  public Response<RrfSmokeResponse> rrf(
      @RequestParam("query") String query,
      @RequestParam(value = "topN", defaultValue = "10") int topN) {
    float[] queryEmbedding = embeddingClient.embed(query);
    int topK = topN * 3;
    List<SparseSearchResult> sparseResults = sparseQueryService.search(query, topK);
    List<DenseSearchResult> denseResults = denseQueryService.search(queryEmbedding, topK);
    List<RrfFusionResult> fusedResults = rrfFusionService.fuse(sparseResults, denseResults, topN);
    return Response.success(
        new RrfSmokeResponse(query, sparseResults.size(), denseResults.size(), fusedResults));
  }

  /**
   * Rerank 重排序阶段冒烟测试 —— 对比 RRF 融合结果在 Rerank 前后的排序变化.
   *
   * <p>先运行 Embed → Sparse → Dense → RRF，再对 RRF 融合结果执行语义 Rerank， 返回排序前后两组完整结果列表，便于验证 Rerank
   * 的语义重排序效果.
   *
   * @param query 用户查询文本
   * @param topN 最终返回数量，默认 10
   * @return RerankSmokeResponse 含 Rerank 前后对比
   */
  @GetMapping("/rerank")
  public Response<RerankSmokeResponse> rerank(
      @RequestParam("query") String query,
      @RequestParam(value = "topN", defaultValue = "10") int topN) {
    float[] queryEmbedding = embeddingClient.embed(query);
    int topK = topN * 3;
    List<SparseSearchResult> sparseResults = sparseQueryService.search(query, topK);
    List<DenseSearchResult> denseResults = denseQueryService.search(queryEmbedding, topK);
    List<RrfFusionResult> fusedResults = rrfFusionService.fuse(sparseResults, denseResults, topN);
    List<ChunkSearchResult> rerankedResults = rerankService.rerank(query, fusedResults);
    return Response.success(new RerankSmokeResponse(query, fusedResults, rerankedResults));
  }

  /**
   * 冒烟测试响应体.
   *
   * @param status HTTP 服务状态
   * @param database 数据库连接状态
   * @param tables 核心数据表记录数
   */
  public record SmokeResponse(String status, String database, SmokeTableCounts tables) {}

  /**
   * 冒烟测试表计数.
   *
   * @param chunk chunk 表记录数
   * @param chunkEmbedding chunk_embedding 表记录数
   * @param chunkFts chunk_fts 表记录数
   */
  public record SmokeTableCounts(
      long chunk,
      @JsonProperty("chunk_embedding") long chunkEmbedding,
      @JsonProperty("chunk_fts") long chunkFts) {}

  /**
   * 检索冒烟测试响应体 —— 通过 RetrievalService.retrieve(...) 全链路获取结果.
   *
   * @param query 用户查询文本
   * @param results Rerank 重排序后的最终结果列表（含邻接扩展 + 四路得分）
   */
  public record RetrievalSmokeResponse(
      String query, @JsonProperty("results") List<ChunkSearchResult> results) {}

  /**
   * chunk 写入请求体.
   *
   * @param title 文档标题
   * @param content 文档正文（将被存入 parent + child chunk）
   */
  public record ChunkWriteRequest(String title, String content) {}

  /**
   * chunk 写入响应体.
   *
   * @param docId 文档 ID
   * @param chunkCount child chunk 数量
   * @param childChunkIds child chunk ID 列表
   * @param parentChunkIds parent chunk ID 列表
   */
  public record ChunkWriteResponse(
      long docId,
      int chunkCount,
      @JsonProperty("child_chunk_ids") List<Long> childChunkIds,
      @JsonProperty("parent_chunk_ids") List<Long> parentChunkIds) {}

  /**
   * chunk 索引数据查询响应体.
   *
   * @param chunkId chunk ID
   * @param embeddingExists chunk_embedding 表是否存在对应行
   * @param ftsExists chunk_fts 表是否存在对应行
   * @param denseStatus chunk 主表的 dense_status（枚举名）
   * @param sparseStatus chunk 主表的 sparse_status（枚举名）
   */
  public record ChunkIndexesResponse(
      @JsonProperty("chunk_id") long chunkId,
      @JsonProperty("embedding_exists") boolean embeddingExists,
      @JsonProperty("fts_exists") boolean ftsExists,
      @JsonProperty("dense_status") String denseStatus,
      @JsonProperty("sparse_status") String sparseStatus) {}

  /**
   * RRF 融合阶段冒烟测试响应体.
   *
   * @param query 用户查询文本
   * @param sparseCount Sparse FTS 检索结果数
   * @param denseCount Dense 向量检索结果数
   * @param fusedResults RRF 融合后的完整结果列表（不含 Rerank）
   */
  public record RrfSmokeResponse(
      String query,
      int sparseCount,
      int denseCount,
      @JsonProperty("fused_results") List<RrfFusionResult> fusedResults) {}

  /**
   * Rerank 重排序阶段冒烟测试响应体.
   *
   * @param query 用户查询文本
   * @param beforeRerank RRF 融合结果（Rerank 前）
   * @param afterRerank Rerank 重排序后的结果
   */
  public record RerankSmokeResponse(
      String query,
      @JsonProperty("before_rerank") List<RrfFusionResult> beforeRerank,
      @JsonProperty("after_rerank") List<ChunkSearchResult> afterRerank) {}
}
