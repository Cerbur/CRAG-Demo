package com.crag.demo.core.chunk;

import org.springframework.stereotype.Component;

/**
 * 文档分块服务 —— 将原始文本拆分为 child chunk + parent chunk.
 *
 * 入库流程第一步：基于 TokenTextSplitter 按 256 token 切 child，1024 token 切 parent.
 * Parent chunk 仅存储纯文本，不参与向量化；child chunk 为唯一检索+向量化粒度.
 *
 * @since 2026-06-10
 */
@Component
public class ChunkService {

    /**
     * 对原始文本执行分块（骨架，plan_2 实现）.
     *
     * @param content 原始文本
     */
    public void split(String content) {
        // plan_2 实现
    }
}
