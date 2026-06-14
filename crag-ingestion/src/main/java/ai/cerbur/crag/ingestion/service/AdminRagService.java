package ai.cerbur.crag.ingestion.service;

import ai.cerbur.crag.ingestion.chunk.split.ChunkSplitGroup;
import ai.cerbur.crag.ingestion.chunk.split.ChunkSplitResult;
import ai.cerbur.crag.ingestion.chunk.split.ChunkSplitService;
import ai.cerbur.crag.storage.entity.Chunk;
import ai.cerbur.crag.storage.ChunkDao;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 管理端 RAG 服务 —— 知识库入库编排（分块 + 写入 + 异步向量化）.
 *
 * 同步写入链路：接收纯文本 → ChunkSplitService 分块 → Chunk 工厂方法构造实体 → ChunkRepository 批量写入.
 * Parent chunk 在写入时即设 dense_status=SKIPPED / sparse_status=SKIPPED，不做后续向量化.
 * Child chunk 设 dense_status=INIT / sparse_status=INIT，等待 Cron 异步处理.
 *
 * @since 2026-06-10
 */
@Service
public class AdminRagService {

    private static final Logger log = LoggerFactory.getLogger(AdminRagService.class);

    /**
     * 文档分块服务 —— 将纯文本拆分为 parent + child chunks.
     */
    @Autowired
    private ChunkSplitService chunkSplitService;

    /**
     * Chunk 表 DAO —— 批量写入 chunk 实体.
     */
    @Autowired
    private ChunkDao chunkDao;

    /**
     * Jackson ObjectMapper —— 序列化 chunk metadata 为 JSON 字符串.
     */
    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 知识入库 —— 接收纯文本，分块后写入 chunk 表，返回入库结果.
     *
     * 流程：
     * <ol>
     *   <li>生成 docId</li>
     *   <li>构建 metadata JSON（title + 扩展元数据合并）</li>
     *   <li>ChunkSplitService.split 分块</li>
     *   <li>遍历 parent group：预生成 parent chunkId → 构造 parent + children（打平到一个 list）→ 一步 saveAll</li>
     *   <li>返回 AdminRagResult(docId, childCount, PENDING)</li>
     * </ol>
     *
     * @param title    文档标题，存入 chunk.metadata JSON
     * @param content  文档纯文本内容
     * @param metadata 扩展元数据（tags 等），与 title 合并存入 chunk.metadata
     * @return AdminRagResult 含 docId、child chunk 数量、"PENDING" 状态
     */
    public AdminRagResult ingest(String title, String content, Map<String, Object> metadata) {
        String docId = UUID.randomUUID().toString();
        String metadataJson = buildMetadataJson(title, metadata, docId);

        ChunkSplitResult splitResult = chunkSplitService.split(content);
        List<ChunkSplitGroup> groups = splitResult.chunkGroups();

        if (groups.isEmpty()) {
            log.info("No chunks produced for docId={}, title={}", docId, title);
            return new AdminRagResult(docId, 0, "PENDING");
        }

        List<Chunk> allChunks = new ArrayList<>();
        int childCount = 0;

        for (ChunkSplitGroup group : groups) {
            Chunk parent = Chunk.createParent(docId,
                group.parentChunk().content(),
                group.parentChunk().tokenCount(),
                group.parentChunk().chunkIndex(),
                metadataJson);
            allChunks.add(parent);

            for (var childData : group.childChunks()) {
                Chunk child = Chunk.createChild(docId,
                    parent.getChunkId(),
                    childData.content(),
                    childData.tokenCount(),
                    childData.chunkIndex(),
                    metadataJson);
                allChunks.add(child);
            }
            childCount += group.childChunks().size();
        }

        chunkDao.saveAll(allChunks);

        log.info("Document ingested: docId={}, title={}, parentGroups={}, childChunks={}, status=PENDING",
            docId, title, groups.size(), childCount);

        return new AdminRagResult(docId, childCount, "PENDING");
    }

    private String buildMetadataJson(String title, Map<String, Object> metadata, String docId) {
        Map<String, Object> enriched = new LinkedHashMap<>();
        enriched.put("title", title);
        if (metadata != null) {
            enriched.putAll(metadata);
        }
        try {
            return objectMapper.writeValueAsString(enriched);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize chunk metadata for docId=" + docId, e);
        }
    }
}
