package ai.cerbur.crag.console.document.controller;

import ai.cerbur.crag.common.dto.result.Response;
import ai.cerbur.crag.common.dto.result.ResponseCode;
import ai.cerbur.crag.console.document.dto.DocumentListResponse;
import ai.cerbur.crag.console.document.dto.DocumentResponse;
import ai.cerbur.crag.console.document.service.KnowledgeDocumentClient;
import ai.cerbur.crag.console.document.service.UploadValidation;
import ai.cerbur.crag.console.document.service.UploadValidation.UploadInvalidException;
import ai.cerbur.crag.console.document.service.UploadValidation.ValidatedUpload;
import ai.cerbur.crag.console.knowledge.service.KnowledgeBaseOrchestrator;
import ai.cerbur.crag.console.security.filter.BearerTokenAuthenticationFilter;
import ai.cerbur.crag.console.security.jwt.ConsolePrincipal;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Console Document HTTP 入口（plan_21/21.8）。
 *
 * <p>路由：list/upload/get/retry。actor userId 只来自 ConsolePrincipal；路径参数 {@code tenantId}/{@code
 * knowledgeBaseId}/{@code docId} 不接受 body 覆盖。每个 operation 先 KB 归属
 * Authorize(TENANT_VIEW_KNOWLEDGE_BASE)， 再调 Knowledge Document。
 *
 * <p>upload 接受单文件 multipart（10 MiB、.txt/.md、UTF-8），先 Authorize UPLOAD_ACTION 校验，再通过 {@link
 * KnowledgeDocumentClient} 流式上传。上传成功返回 HTTP 202 + PENDING；底层 Document 创建在 Knowledge 内返回 201。
 * 不下载或删除文件。
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/knowledge-bases/{knowledgeBaseId}/documents")
@Validated
public class DocumentController {

  private final KnowledgeBaseOrchestrator kbOrchestrator;
  private final KnowledgeDocumentClient documentClient;

  public DocumentController(
      KnowledgeBaseOrchestrator kbOrchestrator, KnowledgeDocumentClient documentClient) {
    this.kbOrchestrator = kbOrchestrator;
    this.documentClient = documentClient;
  }

  @GetMapping
  public ResponseEntity<Response<DocumentListResponse>> list(
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
    // KB 归属先 Authorize VIEW（跨租户/不存在统一 404，不泄漏）
    kbOrchestrator.get(principal.userId(), tenantId, knowledgeBaseId);
    DocumentListResponse page = documentClient.list(tenantId, knowledgeBaseId, pageSize, pageToken);
    return ResponseEntity.ok(Response.success(page));
  }

  @PostMapping
  public ResponseEntity<Response<DocumentResponse>> upload(
      @RequestAttribute(value = BearerTokenAuthenticationFilter.PRINCIPAL_ATTR, required = false)
          ConsolePrincipal principal,
      @PathVariable long tenantId,
      @PathVariable long knowledgeBaseId,
      @RequestParam("file") MultipartFile file) {
    if (principal == null) {
      return ResponseEntity.status(401).body(Response.error(ResponseCode.UNAUTHENTICATED));
    }
    // KB 归属先 Authorize VIEW（跨租户/不存在统一 404，不泄漏）
    kbOrchestrator.get(principal.userId(), tenantId, knowledgeBaseId);
    ValidatedUpload upload;
    try {
      upload = UploadValidation.validate(file, tenantId, knowledgeBaseId, principal.userId());
    } catch (UploadInvalidException e) {
      // 校验失败直接抛出，由 GlobalExceptionHandler 按 reason 映射稳定错误码
      throw e;
    }
    ai.cerbur.crag.contracts.knowledge.v1.Document created = documentClient.upload(upload);
    // upload 返回 HTTP 202 + PENDING 投影；底层 create 在 Knowledge 内返回 201
    return ResponseEntity.status(202)
        .body(Response.success(KnowledgeDocumentClient.toResponse(created)));
  }

  @GetMapping("/{docId}")
  public ResponseEntity<Response<DocumentResponse>> get(
      @RequestAttribute(value = BearerTokenAuthenticationFilter.PRINCIPAL_ATTR, required = false)
          ConsolePrincipal principal,
      @PathVariable long tenantId,
      @PathVariable long knowledgeBaseId,
      @PathVariable long docId) {
    if (principal == null) {
      return ResponseEntity.status(401).body(Response.error(ResponseCode.UNAUTHENTICATED));
    }
    kbOrchestrator.get(principal.userId(), tenantId, knowledgeBaseId);
    DocumentResponse response = documentClient.get(tenantId, knowledgeBaseId, docId);
    return ResponseEntity.ok(Response.success(response));
  }

  @PostMapping("/{docId}/ingestion/retry")
  public ResponseEntity<Response<DocumentResponse>> retry(
      @RequestAttribute(value = BearerTokenAuthenticationFilter.PRINCIPAL_ATTR, required = false)
          ConsolePrincipal principal,
      @PathVariable long tenantId,
      @PathVariable long knowledgeBaseId,
      @PathVariable long docId) {
    if (principal == null) {
      return ResponseEntity.status(401).body(Response.error(ResponseCode.UNAUTHENTICATED));
    }
    kbOrchestrator.get(principal.userId(), tenantId, knowledgeBaseId);
    DocumentResponse response =
        documentClient.retry(principal.userId(), tenantId, knowledgeBaseId, docId);
    return ResponseEntity.ok(Response.success(response));
  }
}
