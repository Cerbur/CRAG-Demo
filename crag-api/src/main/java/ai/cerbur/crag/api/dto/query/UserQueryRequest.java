package ai.cerbur.crag.api.dto.query;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 用户查询请求 DTO —— POST /api/v1/query 入参.
 *
 * <p>紧凑构造器会对 question 执行 trim，校验在 trim 后的值上进行.
 *
 * @param question 用户自然语言问题，不可为空，长度 1–2000 字符
 * @since 2026-06-13
 */
public record UserQueryRequest(
    @NotBlank(message = "question is required")
        @Size(max = 2000, message = "question must not exceed 2000 characters")
        String question) {

  /** 紧凑构造器 —— 对 question 执行 trim，校验在 trim 后的值上进行. */
  public UserQueryRequest {
    if (question != null) {
      question = question.trim();
    }
  }
}
