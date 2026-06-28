package ai.cerbur.crag.console.knowledge.controller;

import ai.cerbur.crag.common.dto.result.Response;
import ai.cerbur.crag.common.dto.result.ResponseCode;
import ai.cerbur.crag.console.knowledge.dto.CreateKnowledgeBaseRequest;
import ai.cerbur.crag.console.knowledge.dto.KnowledgeBaseListResponse;
import ai.cerbur.crag.console.knowledge.dto.KnowledgeBaseResponse;
import ai.cerbur.crag.console.knowledge.service.KnowledgeBaseOrchestrator;
import ai.cerbur.crag.console.security.filter.BearerTokenAuthenticationFilter;
import ai.cerbur.crag.console.security.jwt.ConsolePrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Console KnowledgeBase HTTP 入口（plan_21/21.8）。
 *
 * <p>路由：list/create/get。actor userId 只来自 ConsolePrincipal；路径参数 {@code tenantId} 与 {@code
 * knowledgeBaseId} 不接受 body 覆盖。建库 create 返回 HTTP 201（即使 Scope 部分失败仍 201，{@code apiKeyReady=false}）。
 * list/get 先 Authorize，跨租户统一 404，不泄漏存在性。
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/knowledge-bases")
@Validated
public class KnowledgeBaseController {

  private final KnowledgeBaseOrchestrator orchestrator;

  public KnowledgeBaseController(KnowledgeBaseOrchestrator orchestrator) {
    this.orchestrator = orchestrator;
  }

  @GetMapping
  public ResponseEntity<Response<KnowledgeBaseListResponse>> list(
      @RequestAttribute(value = BearerTokenAuthenticationFilter.PRINCIPAL_ATTR, required = false)
          ConsolePrincipal principal,
      @PathVariable long tenantId,
      @RequestParam(value = "pageSize", defaultValue = "20")
          @Min(value = 1, message = "pageSize must be >= 1")
          @Max(value = 100, message = "pageSize must be <= 100")
          int pageSize,
      @RequestParam(value = "pageToken", defaultValue = "") String pageToken) {
    if (principal == null) {
      return ResponseEntity.status(401).body(Response.error(ResponseCode.UNAUTHENTICATED));
    }
    if (pageSize < 1 || pageSize > 100) {
      throw new IllegalArgumentException("pageSize must be 1-100");
    }
    KnowledgeBaseListResponse page =
        orchestrator.list(principal.userId(), tenantId, pageSize, pageToken);
    return ResponseEntity.ok(Response.success(page));
  }

  @PostMapping
  public ResponseEntity<Response<KnowledgeBaseResponse>> create(
      @RequestAttribute(value = BearerTokenAuthenticationFilter.PRINCIPAL_ATTR, required = false)
          ConsolePrincipal principal,
      @PathVariable long tenantId,
      @Valid @RequestBody CreateKnowledgeBaseRequest body) {
    if (principal == null) {
      return ResponseEntity.status(401).body(Response.error(ResponseCode.UNAUTHENTICATED));
    }
    KnowledgeBaseResponse response =
        orchestrator.create(principal.userId(), tenantId, body.name()).response();
    // 建库 create 返回 201（即使 Scope 部分失败仍 201）
    return ResponseEntity.status(201).body(Response.success(response));
  }

  @GetMapping("/{knowledgeBaseId}")
  public ResponseEntity<Response<KnowledgeBaseResponse>> get(
      @RequestAttribute(value = BearerTokenAuthenticationFilter.PRINCIPAL_ATTR, required = false)
          ConsolePrincipal principal,
      @PathVariable long tenantId,
      @PathVariable long knowledgeBaseId) {
    if (principal == null) {
      return ResponseEntity.status(401).body(Response.error(ResponseCode.UNAUTHENTICATED));
    }
    KnowledgeBaseResponse response =
        orchestrator.get(principal.userId(), tenantId, knowledgeBaseId);
    return ResponseEntity.ok(Response.success(response));
  }
}
