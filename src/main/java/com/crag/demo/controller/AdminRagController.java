package com.crag.demo.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 管理端 RAG 知识库上传接口 —— 接收纯文本内容，分块入库并异步完成向量化.
 *
 * POST /api/v1/admin/rag，接收纯文本 JSON，返回 docId 及分块数量.
 *
 * @since 2026-06-10
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminRagController {

    /**
     * 知识库上传接口（骨架）.
     *
     * @param body 包含 "title"、"content"、"metadata" 的 JSON
     * @return 空 JSON（plan_2 实现完整入库链路）
     */
    @PostMapping("/rag")
    public Map<String, Object> upload(@RequestBody Map<String, Object> body) {
        return Map.of(
            "docId", "00000000-0000-0000-0000-000000000000",
            "chunks", 0,
            "status", "OK"
        );
    }
}
