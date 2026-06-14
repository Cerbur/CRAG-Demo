package ai.cerbur.crag.app.controller;

import ai.cerbur.crag.common.dto.result.Response;
import ai.cerbur.crag.storage.ChunkDao;
import ai.cerbur.crag.storage.ChunkEmbeddingDao;
import ai.cerbur.crag.storage.ChunkFtsDao;
import com.fasterxml.jackson.annotation.JsonProperty;
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
    public Response<SmokeResponse> smoke() {
        long chunkCount = chunkDao.count();
        long embeddingCount = chunkEmbeddingDao.count();
        long ftsCount = chunkFtsDao.count();

        SmokeTableCounts tables = new SmokeTableCounts(chunkCount, embeddingCount, ftsCount);
        return Response.success(new SmokeResponse("ok", "connected", tables));
    }

    /**
     * 冒烟测试响应体.
     *
     * 用显式结构表达 HTTP、数据库连接和核心表计数结果，避免 Controller 返回裸 Map.
     *
     * @param status HTTP 服务状态
     * @param database 数据库连接状态
     * @param tables 核心数据表记录数
     */
    public record SmokeResponse(String status, String database, SmokeTableCounts tables) {
    }

    /**
     * 冒烟测试表计数.
     *
     * 记录 chunk、chunk_embedding、chunk_fts 三张核心表的当前记录数.
     *
     * @param chunk chunk 表记录数
     * @param chunkEmbedding chunk_embedding 表记录数
     * @param chunkFts chunk_fts 表记录数
     */
    public record SmokeTableCounts(long chunk,
                                    @JsonProperty("chunk_embedding") long chunkEmbedding,
                                    @JsonProperty("chunk_fts") long chunkFts) {
    }
}
