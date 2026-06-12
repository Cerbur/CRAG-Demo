package com.crag.demo.core.sparse;

import org.springframework.stereotype.Component;

/**
 * BM25 稀疏查询服务 —— 基于 PostgreSQL FTS（全文检索）在 child chunk 维度做关键词检索.
 *
 * 检索流水线并行分支之一，与 DenseQuery 同时发出，结果经 RRF 融合.
 *
 * @since 2026-06-10
 */
@Component
public class SparseQueryService {

    /**
     * BM25 关键词检索（骨架，plan_2 实现）.
     *
     * @param query 用户问题
     * @param topK  返回数量
     * @return 空列表
     */
    public java.util.List<?> search(String query, int topK) {
        return java.util.Collections.emptyList();
    }
}
