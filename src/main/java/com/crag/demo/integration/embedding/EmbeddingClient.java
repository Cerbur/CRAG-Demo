package com.crag.demo.integration.embedding;

/**
 * EmbeddingClient 统一接口 —— 文本向量化.
 *
 * 一期实现：Sidecar Python 容器（FastAPI + text2vec-large-chinese），HTTP POST /embed.
 * 输入文本，返回 float[] 稠密向量.
 *
 * @since 2026-06-10
 */
public interface EmbeddingClient {

    /**
     * 将文本转为向量（骨架，plan_2 实现）.
     *
     * @param text 输入文本
     * @return 空数组
     */
    float[] embed(String text);
}
