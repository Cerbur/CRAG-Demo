package ai.cerbur.crag.console.apikey.controller;

import ai.cerbur.crag.common.dto.result.Response;
import ai.cerbur.crag.common.dto.result.ResponseCode;
import ai.cerbur.crag.console.apikey.dto.ApiKeyListResponse;
import ai.cerbur.crag.console.apikey.dto.ApiKeyResponse;
import ai.cerbur.crag.console.apikey.dto.CreateApiKeyRequest;
import ai.cerbur.crag.console.apikey.dto.CreatedApiKeyResponse;
import ai.cerbur.crag.console.apikey.service.ApiKeyOrchestrator;
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
 * Console API Key HTTP 入口（plan_21/21.9）。
 *
 * <p>路由：list/get/create/disable/enable/rotate/revoke，嵌套在 KB 路径下。actor userId 只来自
 * ConsolePrincipal，{@code tenantId}/{@code knowledgeBaseId} 只来自路径参数，不接受 body 覆盖，防越权。只有 OWNER
 * 可管理（Access 实时授权返回 PERMISSION_DENIED → 403）；跨 KB 不存在统一 404，不泄漏存在性；状态冲突（disable 已 DISABLED、revoke 已
 * REVOKED、rotate DISABLED/REVOKED）→ 409。
 *
 * <p>完整 Key（{@code completeKey}）只在 create 与 rotate 响应（{@link CreatedApiKeyResponse}）中返回一次；
 * list/get/disable/enable/revoke 只返回前缀投影（{@link ApiKeyResponse}）。
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/knowledge-bases/{knowledgeBaseId}/api-keys")
@Validated
public class ApiKeyController {

  private final ApiKeyOrchestrator orchestrator;

  public ApiKeyController(ApiKeyOrchestrator orchestrator) {
    this.orchestrator = orchestrator;
  }

  @GetMapping
  public ResponseEntity<Response<ApiKeyListResponse>> list(
      @RequestAttribute(value = BearerTokenAuthenticationFilter.PRINCIPAL_ATTR, required = false)
          ConsolePrincipal principal,
      @PathVariable long tenantId,
      @PathVariable long knowledgeBaseId,
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
    ApiKeyListResponse page =
        orchestrator.list(principal.userId(), tenantId, knowledgeBaseId, pageSize, pageToken);
    return ResponseEntity.ok(Response.success(page));
  }

  @PostMapping
  public ResponseEntity<Response<CreatedApiKeyResponse>> create(
      @RequestAttribute(value = BearerTokenAuthenticationFilter.PRINCIPAL_ATTR, required = false)
          ConsolePrincipal principal,
      @PathVariable long tenantId,
      @PathVariable long knowledgeBaseId,
      @Valid @RequestBody CreateApiKeyRequest body) {
    if (principal == null) {
      return ResponseEntity.status(401).body(Response.error(ResponseCode.UNAUTHENTICATED));
    }
    long ttlSeconds = body.ttlSeconds() == null ? 0L : body.ttlSeconds();
    CreatedApiKeyResponse response =
        orchestrator.create(principal.userId(), tenantId, knowledgeBaseId, body.name(), ttlSeconds);
    return ResponseEntity.status(201).body(Response.success(response));
  }

  @GetMapping("/{apiKeyId}")
  public ResponseEntity<Response<ApiKeyResponse>> get(
      @RequestAttribute(value = BearerTokenAuthenticationFilter.PRINCIPAL_ATTR, required = false)
          ConsolePrincipal principal,
      @PathVariable long tenantId,
      @PathVariable long knowledgeBaseId,
      @PathVariable long apiKeyId) {
    if (principal == null) {
      return ResponseEntity.status(401).body(Response.error(ResponseCode.UNAUTHENTICATED));
    }
    ApiKeyResponse response =
        orchestrator.get(principal.userId(), tenantId, knowledgeBaseId, apiKeyId);
    return ResponseEntity.ok(Response.success(response));
  }

  @PostMapping("/{apiKeyId}/disable")
  public ResponseEntity<Response<ApiKeyResponse>> disable(
      @RequestAttribute(value = BearerTokenAuthenticationFilter.PRINCIPAL_ATTR, required = false)
          ConsolePrincipal principal,
      @PathVariable long tenantId,
      @PathVariable long knowledgeBaseId,
      @PathVariable long apiKeyId) {
    if (principal == null) {
      return ResponseEntity.status(401).body(Response.error(ResponseCode.UNAUTHENTICATED));
    }
    ApiKeyResponse response =
        orchestrator.disable(principal.userId(), tenantId, knowledgeBaseId, apiKeyId);
    return ResponseEntity.ok(Response.success(response));
  }

  @PostMapping("/{apiKeyId}/enable")
  public ResponseEntity<Response<ApiKeyResponse>> enable(
      @RequestAttribute(value = BearerTokenAuthenticationFilter.PRINCIPAL_ATTR, required = false)
          ConsolePrincipal principal,
      @PathVariable long tenantId,
      @PathVariable long knowledgeBaseId,
      @PathVariable long apiKeyId) {
    if (principal == null) {
      return ResponseEntity.status(401).body(Response.error(ResponseCode.UNAUTHENTICATED));
    }
    ApiKeyResponse response =
        orchestrator.enable(principal.userId(), tenantId, knowledgeBaseId, apiKeyId);
    return ResponseEntity.ok(Response.success(response));
  }

  @PostMapping("/{apiKeyId}/rotate")
  public ResponseEntity<Response<CreatedApiKeyResponse>> rotate(
      @RequestAttribute(value = BearerTokenAuthenticationFilter.PRINCIPAL_ATTR, required = false)
          ConsolePrincipal principal,
      @PathVariable long tenantId,
      @PathVariable long knowledgeBaseId,
      @PathVariable long apiKeyId) {
    if (principal == null) {
      return ResponseEntity.status(401).body(Response.error(ResponseCode.UNAUTHENTICATED));
    }
    CreatedApiKeyResponse response =
        orchestrator.rotate(principal.userId(), tenantId, knowledgeBaseId, apiKeyId, 0L);
    return ResponseEntity.ok(Response.success(response));
  }

  @PostMapping("/{apiKeyId}/revoke")
  public ResponseEntity<Response<ApiKeyResponse>> revoke(
      @RequestAttribute(value = BearerTokenAuthenticationFilter.PRINCIPAL_ATTR, required = false)
          ConsolePrincipal principal,
      @PathVariable long tenantId,
      @PathVariable long knowledgeBaseId,
      @PathVariable long apiKeyId) {
    if (principal == null) {
      return ResponseEntity.status(401).body(Response.error(ResponseCode.UNAUTHENTICATED));
    }
    ApiKeyResponse response =
        orchestrator.revoke(principal.userId(), tenantId, knowledgeBaseId, apiKeyId);
    return ResponseEntity.ok(Response.success(response));
  }
}
