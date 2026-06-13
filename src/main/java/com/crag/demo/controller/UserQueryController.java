package com.crag.demo.controller;

import com.crag.demo.dto.request.UserQueryRequest;
import com.crag.demo.dto.result.Response;
import jakarta.validation.Valid;
import java.util.Collections;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
     * 用户问答接口（骨架，plan_3 实现完整检索+生成链路）.
     *
     * @param request 含 question 字段的请求体，@Valid 校验非空
     * @return 统一响应，result 含 answer 和 sources
     */
    @PostMapping("/query")
    public Response<Map<String, Object>> query(@Valid @RequestBody UserQueryRequest request) {
        return Response.success(Map.of(
            "answer", "OK",
            "sources", Collections.emptyList()
        ));
    }
}
