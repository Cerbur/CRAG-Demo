package com.crag.demo.controller;

import com.crag.demo.dao.ChunkDao;
import com.crag.demo.dao.ChunkEmbeddingDao;
import com.crag.demo.dao.ChunkFtsDao;
import com.crag.demo.dto.result.Response;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
     * Chunk 主表 DAO，用于验证 chunk 表可查询.
     */
    @Autowired
    private ChunkDao chunkDao;

    /**
     * Chunk Embedding 表 DAO，用于验证 embedding 向量表可查询.
     */
    @Autowired
    private ChunkEmbeddingDao chunkEmbeddingDao;

    /**
     * Chunk FTS 表 DAO，用于验证全文检索表可查询.
     */
    @Autowired
    private ChunkFtsDao chunkFtsDao;

    /**
     * 冒烟测试 —— 查询三张表的记录数，验证 HTTP + JDBC 全链路连通.
     *
     * 三张表即使无数据也会返回 count=0，重点确认 JDBC 连接正常。
     * 数据库不可达时 HikariCP 抛异常，由 GlobalExceptionHandler 统一转为 500 错误响应.
     *
     * @return 统一响应，result 含 status / database / tables 三级信息
     */
    @GetMapping("/smoke")
    public Response<Map<String, Object>> smoke() {
        long chunkCount = chunkDao.count();
        long embeddingCount = chunkEmbeddingDao.count();
        long ftsCount = chunkFtsDao.count();

        return Response.success(Map.of(
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
