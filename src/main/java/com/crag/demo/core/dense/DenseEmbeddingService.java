package com.crag.demo.core.dense;

import org.springframework.stereotype.Component;

/**
 * Dense Embedding 向量化服务 —— 调用 integration/dense 将文本转为稠密向量.
 *
 * 入库阶段：Cron 扫表后，仅对 child chunk 做向量化，写入 pgvector.
 * 检索阶段：将用户 query 向量化后送入 DenseQueryService.
 *
 * @since 2026-06-10
 */
@Component
public class DenseEmbeddingService {

    /**
     * 对文本做向量化（骨架，plan_2 实现）.
     *
     * @param text 输入文本
     * @return 空数组
     */
    public float[] embed(String text) {
        return new float[0];
    }
}
