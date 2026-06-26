package ai.cerbur.crag.ingestion.api;

import ai.cerbur.crag.id.api.CragIdGenerator;
import ai.cerbur.crag.id.api.IdEntityType;
import ai.cerbur.crag.ingestion.chunk.split.ChunkSplitGroup;
import ai.cerbur.crag.ingestion.chunk.split.ChunkSplitResult;
import ai.cerbur.crag.ingestion.chunk.split.ChunkSplitService;
import ai.cerbur.crag.storage.ChunkDao;
import ai.cerbur.crag.storage.entity.Chunk;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 管理端 RAG 服务 —— 知识库入库编排（分块 + 写入 + 异步向量化）.
 *
 * <p>同步写入链路：接收纯文本 → ChunkSplitService 分块 → Chunk 工厂方法构造实体 → ChunkRepository 批量写入. Parent chunk
 * 在写入时即设 dense_status=SKIPPED / sparse_status=SKIPPED，不做后续向量化. Child chunk 设 dense_status=INIT /
 * sparse_status=INIT，等待 Cron 异步处理.
 *
 * @since 2026-06-10
 */
@Service
public class AdminRagService {

  private static final Logger log = LoggerFactory.getLogger(AdminRagService.class);

  /**
   * 旧 smoke AdminRag 写入固定使用的知识库 ID（Plan 19）.
   *
   * <p>旧 AdminRag smoke 入口保留为历史诊断能力，不复用 Knowledge 上传链路，也不作为正式业务入口。它写入的 chunk 必须携带一个固定、且远离 Knowledge
   * demo 身份号段的 {@code knowledgeBaseId}，避免与 router2 通过 Knowledge 创建的 知识库（DB identity 从 1
   * 起的较小号段）发生召回串库。真实业务数据只应通过 Knowledge 上传 → DOC_UPLOADED → Ingestion Job 链路产生。
   */
  public static final long SMOKE_KNOWLEDGE_BASE_ID = 1_000_000_000_000L;

  /** 文档分块服务 —— 将纯文本拆分为 parent + child chunks. */
  @Autowired private ChunkSplitService chunkSplitService;

  /** Chunk 表 DAO —— 批量写入 chunk 实体. */
  @Autowired private ChunkDao chunkDao;

  /** Jackson ObjectMapper —— 序列化 chunk metadata 为 JSON 字符串. */
  @Autowired private ObjectMapper objectMapper;

  /** Snowflake ID 发号器 —— 为文档和 chunk 预生成唯一 long ID. */
  @Autowired private CragIdGenerator cragIdGenerator;

  /**
   * 知识入库 —— 接收纯文本，分块后写入 chunk 表，返回入库结果.
   *
   * <p>流程：
   *
   * <ol>
   *   <li>生成 docId
   *   <li>构建 metadata JSON（title + 扩展元数据合并）
   *   <li>ChunkSplitService.split 分块
   *   <li>遍历 parent group：预生成 parent chunkId → 构造 parent + children（打平到一个 list）→ 一步 saveAll
   *   <li>返回 AdminRagResult(docId, childCount, PENDING)
   * </ol>
   *
   * @param title 文档标题，存入 chunk.metadata JSON
   * @param content 文档纯文本内容
   * @param metadata 扩展元数据（tags 等），与 title 合并存入 chunk.metadata
   * @return AdminRagResult 含 docId、child chunk 数量、"PENDING" 状态
   */
  public AdminRagResult ingest(String title, String content, Map<String, Object> metadata) {
    long docId = cragIdGenerator.nextId(IdEntityType.LEGACY_DOCUMENT);
    long knowledgeBaseId = SMOKE_KNOWLEDGE_BASE_ID;
    String metadataJson = buildMetadataJson(title, metadata, docId);

    ChunkSplitResult splitResult = chunkSplitService.split(content);
    List<ChunkSplitGroup> groups = splitResult.chunkGroups();

    if (groups.isEmpty()) {
      log.info("No chunks produced for docId={}, title={}", docId, title);
      return new AdminRagResult(docId, 0, "PENDING", List.of());
    }

    List<Chunk> allChunks = new ArrayList<>();
    List<Long> parentChunkIds = new ArrayList<>();
    int childCount = 0;

    for (ChunkSplitGroup group : groups) {
      long parentChunkId = cragIdGenerator.nextId(IdEntityType.CHUNK);
      Chunk parent =
          Chunk.createParent(
              parentChunkId,
              knowledgeBaseId,
              docId,
              group.parentChunk().content(),
              group.parentChunk().tokenCount(),
              group.parentChunk().chunkIndex(),
              metadataJson);
      allChunks.add(parent);
      parentChunkIds.add(parentChunkId);

      for (var childData : group.childChunks()) {
        long childChunkId = cragIdGenerator.nextId(IdEntityType.CHUNK);
        Chunk child =
            Chunk.createChild(
                childChunkId,
                knowledgeBaseId,
                docId,
                parentChunkId,
                childData.content(),
                childData.tokenCount(),
                childData.chunkIndex(),
                metadataJson);
        allChunks.add(child);
      }
      childCount += group.childChunks().size();
    }

    chunkDao.saveAll(allChunks);

    log.info(
        "Document ingested: docId={}, knowledgeBaseId={}, title={}, parentGroups={}, childChunks={},"
            + " status=PENDING",
        docId,
        knowledgeBaseId,
        title,
        groups.size(),
        childCount);

    return new AdminRagResult(docId, childCount, "PENDING", parentChunkIds);
  }

  private String buildMetadataJson(String title, Map<String, Object> metadata, long docId) {
    Map<String, Object> enriched = new LinkedHashMap<>();
    enriched.put("title", title);
    if (metadata != null) {
      enriched.putAll(metadata);
    }
    try {
      return objectMapper.writeValueAsString(enriched);
    } catch (JacksonException e) {
      throw new MetadataSerializationException(
          "Failed to serialize chunk metadata for docId=" + docId, e);
    }
  }
}
