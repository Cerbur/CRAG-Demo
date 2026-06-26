package ai.cerbur.crag.smoke.controller;

import ai.cerbur.crag.common.dto.result.Response;
import ai.cerbur.crag.ingestion.api.AdminRagService;
import ai.cerbur.crag.query.api.UserQueryResult;
import ai.cerbur.crag.query.api.UserQueryService;
import ai.cerbur.crag.smoke.dto.query.QuerySourceResponse;
import ai.cerbur.crag.smoke.dto.query.UserQueryRequest;
import ai.cerbur.crag.smoke.dto.query.UserQueryResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户查询接口 —— 接收用户自然语言问题，返回 RAG 生成的回答.
 *
 * <p>POST /api/v1/smoke/query，接收 JSON 请求体，返回答案及引用来源.
 *
 * @since 2026-06-10
 */
@Profile("smoke")
@RestController
@RequestMapping("/api/v1/smoke")
public class UserQueryController {

  @Autowired private UserQueryService userQueryService;

  /**
   * 用户问答接口 —— 委托 UserQueryService 执行检索+生成全链路.
   *
   * @param request 含 question 字段的请求体，@Valid 校验非空及长度
   * @return 统一响应，result 含 answer 和 sources
   */
  @PostMapping("/query")
  public Response<UserQueryResponse> query(@Valid @RequestBody UserQueryRequest request) {
    long knowledgeBaseId =
        (request.knowledgeBaseId() == null || request.knowledgeBaseId() == 0)
            ? AdminRagService.SMOKE_KNOWLEDGE_BASE_ID
            : request.knowledgeBaseId();
    UserQueryResult result = userQueryService.answer(knowledgeBaseId, request.question());
    UserQueryResponse response =
        new UserQueryResponse(
            result.answer(),
            result.sources().stream()
                .map(
                    s ->
                        new QuerySourceResponse(
                            s.reference(),
                            Long.toString(s.parentChunkId()),
                            s.matchedChildIds().stream().map(id -> Long.toString(id)).toList()))
                .toList());
    return Response.success(response);
  }
}
