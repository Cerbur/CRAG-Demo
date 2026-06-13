package com.crag.demo.core.dense;

import java.util.Collections;
import java.util.List;
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

    /**
     * 向量相似度检索（骨架，plan_3 实现）.
     *
     * @param queryEmbedding query 向量
     * @param topK            返回数量
     * @return 空列表
     */
    public List<?> search(float[] queryEmbedding, int topK) {
        return Collections.emptyList();
    }
}
