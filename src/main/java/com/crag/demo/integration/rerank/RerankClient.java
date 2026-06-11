package com.crag.demo.integration.rerank;

import java.util.List;

/**
 * RerankClient 统一接口 —— 对候选文档列表做语义重排序.
 *
 * 一期实现：Sidecar Python 容器（FastAPI + bge-reranker-v2-m3），HTTP POST /rerank.
 * 输入 (query, documents[]) → 返回按相关度降序排列的 RerankResult 列表.
 *
 * @since 2026-06-10
 */
public interface RerankClient {

    /**
     * 对候选文档做语义重排序.
     *
     * @param query     用户问题
     * @param documents 候选文档列表
     * @return 按 score 降序排列的 rerank 结果，每个结果含原始 index 和语义相关度分数
     */
    List<RerankResult> rerank(String query, List<String> documents);

    /**
     * Rerank 单条结果 —— 记录文档在输入列表中的原始位置与语义相关度分数.
     *
     * @param index 文档在输入 documents 列表中的 0-based 位置
     * @param score 语义相关度分数 [0, 1]，越高越相关
     */
    record RerankResult(int index, float score) {}
}
