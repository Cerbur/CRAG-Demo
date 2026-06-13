package com.crag.demo.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * 用户查询请求 DTO —— POST /api/v1/query 入参.
 *
 * @param question 用户自然语言问题，不可为空
 * @since 2026-06-13
 */
public record UserQueryRequest(
    @NotBlank(message = "question is required")
    String question
) {}
