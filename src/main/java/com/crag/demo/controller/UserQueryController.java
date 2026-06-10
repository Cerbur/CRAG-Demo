package com.crag.demo.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 用户查询接口 —— 接收用户自然语言问题，返回 RAG 生成的回答.
 *
 * POST /api/v1/query，接收 JSON 请求体，返回答案及引用来源.
 *
 * @since 2026-06-10
 */
@RestController
@RequestMapping("/api/v1")
public class UserQueryController {

    /**
     * 用户问答接口（骨架）.
     *
     * @param body 包含 "question" 字段的 JSON
     * @return 空 JSON（plan_2 实现完整检索+生成链路）
     */
    @PostMapping("/query")
    public Map<String, Object> query(@RequestBody Map<String, Object> body) {
        return Map.of(
            "answer", "OK",
            "sources", java.util.Collections.emptyList()
        );
    }
}
