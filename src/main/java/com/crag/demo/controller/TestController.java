package com.crag.demo.controller;

import com.crag.demo.dao.repository.ChunkEmbeddingRepository;
import com.crag.demo.dao.repository.ChunkFtsRepository;
import com.crag.demo.dao.repository.ChunkRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 冒烟测试接口 —— 验证 HTTP 可达 + 数据库连通 + 三表可查询.
 *
 * 用于快速验证全链路基础设施是否就绪，不涉及业务逻辑。
 * 一期仅提供 GET /api/v1/test/smoke 端点.
 *
 * @since 2026-06-10
 */
@RestController
@RequestMapping("/api/v1/test")
public class TestController {

    /**
     * Chunk 主表 Repository，用于验证 chunk 表可查询.
     */
    private final ChunkRepository chunkRepository;

    /**
     * Chunk Embedding 表 Repository，用于验证 embedding 向量表可查询.
     */
    private final ChunkEmbeddingRepository chunkEmbeddingRepository;

    /**
     * Chunk FTS 表 Repository，用于验证全文检索表可查询.
     */
    private final ChunkFtsRepository chunkFtsRepository;

    public TestController(ChunkRepository chunkRepository,
                          ChunkEmbeddingRepository chunkEmbeddingRepository,
                          ChunkFtsRepository chunkFtsRepository) {
        this.chunkRepository = chunkRepository;
        this.chunkEmbeddingRepository = chunkEmbeddingRepository;
        this.chunkFtsRepository = chunkFtsRepository;
    }

    /**
     * 冒烟测试 —— 查询三张表的记录数，验证 HTTP + JDBC 全链路连通.
     *
     * 三张表即使无数据也会返回 count=0，重点确认 JDBC 连接正常。
     * 数据库不可达时返回 500 + 错误信息.
     *
     * @return 200 OK + JSON {status, database, tables: {chunk, chunk_embedding, chunk_fts}}
     */
    @GetMapping("/smoke")
    public ResponseEntity<Map<String, Object>> smoke() {
        long chunkCount;
        long embeddingCount;
        long ftsCount;

        try {
            chunkCount = chunkRepository.count();
            embeddingCount = chunkEmbeddingRepository.count();
            ftsCount = chunkFtsRepository.count();
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                "status", "error",
                "database", "disconnected",
                "error", e.getMessage()
            ));
        }

        return ResponseEntity.ok(Map.of(
            "status", "ok",
            "database", "connected",
            "tables", Map.of(
                "chunk", chunkCount,
                "chunk_embedding", embeddingCount,
                "chunk_fts", ftsCount
            )
        ));
    }
}
