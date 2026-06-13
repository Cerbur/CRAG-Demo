package com.crag.demo.core.dense;

import com.crag.demo.integration.dense.EmbeddingClient;
import com.crag.demo.integration.dense.EmbeddingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Dense Embedding 向量化服务 —— 调用 integration/dense 将文本转为稠密向量.
 *
 * 入库阶段：由 Cron 调用，对 child chunk 做向量化后写入 pgvector.
 * 检索阶段：将用户 query 向量化后送入 DenseQueryService（后续 plan 实现）.
 *
 * 本类只负责核心 Embedding 调用逻辑和异常转换，流程编排由 cron/DenseEmbeddingCron 负责.
 *
 * @since 2026-06-10
 */
@Component
public class DenseEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(DenseEmbeddingService.class);

    /**
     * Embedding HTTP 客户端（Sidecar /embed 端点）.
     */
    @Autowired
    private EmbeddingClient embeddingClient;

    /**
     * 对文本做向量化.
     *
     * 直接委托 EmbeddingClient，EmbeddingException 向上传播给 Cron 做终态处理.
     *
     * @param text 输入文本
     * @return float[] 稠密向量（768 维）
     * @throws EmbeddingException Sidecar 调用失败时抛出，由 Cron 捕获并标记 FAILED
     */
    public float[] embed(String text) {
        log.debug("DenseEmbeddingService embedding — text length={}", text.length());
        return embeddingClient.embed(text);
    }
}
