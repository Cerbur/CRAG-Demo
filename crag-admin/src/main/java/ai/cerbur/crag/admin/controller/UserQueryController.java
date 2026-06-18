package ai.cerbur.crag.admin.controller;

import ai.cerbur.crag.admin.dto.request.UserQueryRequest;
import ai.cerbur.crag.common.dto.result.Response;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户查询接口 —— 接收用户自然语言问题，返回 RAG 生成的回答.
 *
 * <p>POST /api/v1/query，接收 JSON 请求体，返回答案及引用来源.
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
  public Response<UserQueryResponse> query(@Valid @RequestBody UserQueryRequest request) {
    return Response.success(new UserQueryResponse("OK", List.of()));
  }

  /**
   * 用户问答响应体.
   *
   * <p>当前仍是查询链路骨架，answer 和 sources 字段用于保持后续 plan_6 全链路响应形态稳定.
   *
   * @param answer 生成回答文本
   * @param sources 引用来源列表
   */
  public record UserQueryResponse(String answer, List<String> sources) {}
}
