package com.crag.demo.service;

import org.springframework.stereotype.Service;

/**
 * 管理端 RAG 服务 —— 知识库入库编排（分块 + 写入 + 异步向量化）.
 *
 * 遵循奥卡姆剃刀：当前只有一个实现，不做 Interface/Impl 分离.
 *
 * @since 2026-06-10
 */
@Service
public class AdminRagService {

    /**
     * 知识入库（骨架，plan_2 实现完整分块+入库链路）.
     *
     * @param title    文档标题
     * @param content  纯文本内容
     * @param metadata 扩展元数据
     */
    public void ingest(String title, String content, String metadata) {
        // plan_2 实现
    }
}
