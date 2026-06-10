package com.crag.demo.core.rrf;

import org.springframework.stereotype.Component;

/**
 * RRF 融合服务 —— 对 Sparse + Dense 两路 child chunk 检索结果做 Reciprocal Rank Fusion 并回表.
 *
 * 融合后通过 parent_chunk_id 回表获取完整 parent 上下文，交给下游 rerank.
 *
 * @since 2026-06-10
 */
@Component
public class RrfFusionService {

    /**
     * RRF 常数 k，防止单路 rank=1 导致分母过小.
     */
    private static final int RRF_K = 60;

    /**
     * 对两路 child chunk 结果执行 RRF 融合，回表取 parent chunk（骨架，plan_2 实现）.
     *
     * @param sparseResults BM25 检索结果（child chunk 维度）
     * @param denseResults  pgvector 检索结果（child chunk 维度）
     * @param topN          融合后保留数量
     * @return 空列表
     */
    public java.util.List<?> fuse(java.util.List<?> sparseResults,
                                   java.util.List<?> denseResults,
                                   int topN) {
        return java.util.Collections.emptyList();
    }
}
