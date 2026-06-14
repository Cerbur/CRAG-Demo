package ai.cerbur.crag.retrieval.sparse;

import ai.cerbur.crag.storage.ChunkFtsDao;
import ai.cerbur.crag.storage.ChunkSearchResult;
import java.util.Collections;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Sparse 稀疏查询服务 —— 基于 PostgreSQL FTS（全文检索）在 child chunk 维度做关键词检索.
 *
 * 检索流水线并行分支之一，与 DenseQuery 同时发出，结果经 RRF 融合.
 *
 * @since 2026-06-10
 */
@Component
public class SparseQueryService {

    @Autowired
    private ChunkFtsDao chunkFtsDao;

    /**
     * FTS 关键词检索.
     *
     * 委托 ChunkFtsDao.searchFts 执行 PostgreSQL 全文检索查询（CJK 预处理在 DB 侧完成），
     * topK 控制返回数量，空查询返回空列表.
     *
     * @param query 用户问题
     * @param topK  返回数量
     * @return 按 ts_rank 降序排列的 ChunkSearchResult 列表
     */
    public List<ChunkSearchResult> search(String query, int topK) {
        if (query == null || query.isBlank() || topK <= 0) {
            return Collections.emptyList();
        }
        return chunkFtsDao.searchFts(query, topK);
    }
}
