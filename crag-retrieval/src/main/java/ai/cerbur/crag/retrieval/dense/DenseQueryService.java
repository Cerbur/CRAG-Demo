package ai.cerbur.crag.retrieval.dense;

import ai.cerbur.crag.storage.ChunkEmbeddingDao;
import ai.cerbur.crag.storage.ChunkSearchResult;
import java.util.Collections;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Dense 稠密查询服务 —— 基于 pgvector 向量相似度在 child chunk 维度做语义检索.
 *
 * 检索流水线并行分支之一，与 SparseQuery 同时发出，结果经 RRF 融合.
 *
 * @since 2026-06-10
 */
@Component
public class DenseQueryService {

    @Autowired
    private ChunkEmbeddingDao chunkEmbeddingDao;

    /**
     * 向量相似度检索.
     *
     * 委托 ChunkEmbeddingDao.searchSimilar 执行 pgvector {@code <=>} 余弦相似度查询，
     * topK 控制返回数量，空向量时返回空列表.
     *
     * @param queryEmbedding query 向量（768 维）
     * @param topK           返回数量
     * @return 按余弦相似度降序排列的 ChunkSearchResult 列表
     */
    public List<ChunkSearchResult> search(float[] queryEmbedding, int topK) {
        if (queryEmbedding == null || queryEmbedding.length == 0 || topK <= 0) {
            return Collections.emptyList();
        }
        return chunkEmbeddingDao.searchSimilar(queryEmbedding, topK);
    }
}
