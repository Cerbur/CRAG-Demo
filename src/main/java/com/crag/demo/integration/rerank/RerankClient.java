package com.crag.demo.integration.rerank;

/**
 * RerankClient 统一接口 —— 对候选文档列表做语义重排序.
 *
 * 一期实现：Sidecar Python 容器（FastAPI + bge-reranker-v2-m3），HTTP POST /rerank.
 * 输入 (query, documents[]) → 输出排序后的 documents[] + scores[].
 *
 * @since 2026-06-10
 */
public interface RerankClient {

    /**
     * 对候选文档做语义重排序（骨架，plan_2 实现）.
     *
     * @param query      用户问题
     * @param documents  候选文档列表
     * @return 空列表
     */
    java.util.List<?> rerank(String query, java.util.List<String> documents);
}
