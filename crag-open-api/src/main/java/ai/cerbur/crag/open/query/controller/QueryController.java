package ai.cerbur.crag.open.query.controller;

import ai.cerbur.crag.common.dto.result.Response;
import ai.cerbur.crag.open.auth.service.BearerApiKeyExtractor;
import ai.cerbur.crag.open.query.dto.QueryRequest;
import ai.cerbur.crag.open.query.dto.QueryResponse;
import ai.cerbur.crag.open.query.service.OpenQueryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Open Query HTTP 入口（plan_21/21.10）。
 *
 * <p>{@code POST /api/v1/query} 只从 {@code Authorization: Bearer crag_...} 读取 Key；请求体只有 {@code
 * question}， 不接受 {@code tenantId} / {@code knowledgeBaseId}。
 */
@RestController
@RequestMapping("/api/v1/query")
public class QueryController {

  @Autowired private OpenQueryService queryService;

  @PostMapping
  public Response<QueryResponse> query(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @Valid @RequestBody QueryRequest request,
      HttpServletRequest httpRequest) {
    String completeKey = BearerApiKeyExtractor.extract(authorization);
    String traceId = resolveTraceId(httpRequest);
    QueryResponse response = queryService.query(completeKey, request.question().trim(), traceId);
    return Response.success(response);
  }

  private String resolveTraceId(HttpServletRequest request) {
    String header = request.getHeader("X-Request-Id");
    if (header != null && !header.isBlank()) {
      return header;
    }
    Object attr = request.getAttribute("traceId");
    if (attr instanceof String s && !s.isBlank()) {
      return s;
    }
    return UUID.randomUUID().toString();
  }
}
