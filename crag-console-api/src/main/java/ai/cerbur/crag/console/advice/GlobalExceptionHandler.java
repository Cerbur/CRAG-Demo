package ai.cerbur.crag.console.advice;

import ai.cerbur.crag.common.dto.error.ErrorDetail;
import ai.cerbur.crag.common.dto.error.FieldErrorDetail;
import ai.cerbur.crag.common.dto.result.Response;
import ai.cerbur.crag.common.dto.result.ResponseCode;
import ai.cerbur.crag.console.apikey.service.ApiKeyOrchestrator;
import ai.cerbur.crag.console.auth.service.AccessIdentityClient.DownstreamTimeoutException;
import ai.cerbur.crag.console.auth.service.AccessIdentityClient.DownstreamUnavailableException;
import ai.cerbur.crag.console.auth.service.AccessIdentityClient.InvalidCredentialsException;
import ai.cerbur.crag.console.auth.service.InvalidOriginException;
import ai.cerbur.crag.console.document.service.KnowledgeDocumentClient;
import ai.cerbur.crag.console.document.service.UploadValidation.Reason;
import ai.cerbur.crag.console.document.service.UploadValidation.UploadInvalidException;
import ai.cerbur.crag.console.knowledge.service.KnowledgeBaseOrchestrator;
import ai.cerbur.crag.console.membership.service.AccessMembershipClient;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Console 统一异常映射（plan_21/21.6）。
 *
 * <p>共享 crag-common 的 {@link ErrorDetail}/{@link ResponseCode}。敏感校验错误不回显
 * rejectedValue；登录/Refresh/API Key 失败原因不通过 message/reason 泄漏。生成 traceId 写入响应。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<Response<ErrorDetail>> handleValidation(
      MethodArgumentNotValidException ex, HttpServletRequest request) {
    List<FieldErrorDetail> fields = new ArrayList<>();
    for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
      fields.add(new FieldErrorDetail(fe.getField(), safeMessage(fe), rejectedValueOrNull(fe)));
    }
    return build(
        ResponseCode.VALIDATION_ERROR,
        new ErrorDetail("Validation failed", traceId(request), "VALIDATION_ERROR", false, fields));
  }

  @ExceptionHandler(InvalidCredentialsException.class)
  public ResponseEntity<Response<ErrorDetail>> handleInvalidCredentials(
      InvalidCredentialsException ex, HttpServletRequest request) {
    // 不泄漏具体凭据失败原因
    return build(
        ResponseCode.INVALID_CREDENTIALS,
        new ErrorDetail("Authentication failed", traceId(request), "INVALID_CREDENTIALS", false));
  }

  @ExceptionHandler(InvalidOriginException.class)
  public ResponseEntity<Response<ErrorDetail>> handleOrigin(
      InvalidOriginException ex, HttpServletRequest request) {
    return build(
        ResponseCode.FORBIDDEN,
        new ErrorDetail("Forbidden", traceId(request), "CROSS_SITE_ORIGIN", false));
  }

  @ExceptionHandler(DownstreamUnavailableException.class)
  public ResponseEntity<Response<ErrorDetail>> handleDownstream(
      DownstreamUnavailableException ex, HttpServletRequest request) {
    return build(
        ResponseCode.DOWNSTREAM_UNAVAILABLE,
        new ErrorDetail(
            "Downstream unavailable", traceId(request), "DOWNSTREAM_UNAVAILABLE", true));
  }

  @ExceptionHandler(DownstreamTimeoutException.class)
  public ResponseEntity<Response<ErrorDetail>> handleDownstreamTimeout(
      DownstreamTimeoutException ex, HttpServletRequest request) {
    return build(
        ResponseCode.DOWNSTREAM_TIMEOUT,
        new ErrorDetail("Downstream timeout", traceId(request), "DOWNSTREAM_TIMEOUT", true));
  }

  // ---- plan_21/21.7 Membership 异常映射 ----

  @ExceptionHandler(AccessMembershipClient.ForbiddenException.class)
  public ResponseEntity<Response<ErrorDetail>> handleMembershipForbidden(
      AccessMembershipClient.ForbiddenException ex, HttpServletRequest request) {
    return build(
        ResponseCode.FORBIDDEN, new ErrorDetail("Forbidden", traceId(request), "FORBIDDEN", false));
  }

  @ExceptionHandler(AccessMembershipClient.NotFoundException.class)
  public ResponseEntity<Response<ErrorDetail>> handleMembershipNotFound(
      AccessMembershipClient.NotFoundException ex, HttpServletRequest request) {
    // 跨租户/不存在统一 not found，不泄漏成员关系存在性
    return build(
        ResponseCode.NOT_FOUND,
        new ErrorDetail("Resource not found", traceId(request), "NOT_FOUND", false));
  }

  @ExceptionHandler(AccessMembershipClient.ConflictException.class)
  public ResponseEntity<Response<ErrorDetail>> handleMembershipConflict(
      AccessMembershipClient.ConflictException ex, HttpServletRequest request) {
    return build(
        ResponseCode.CONFLICT, new ErrorDetail("Conflict", traceId(request), "CONFLICT", false));
  }

  @ExceptionHandler(AccessMembershipClient.DownstreamUnavailableException.class)
  public ResponseEntity<Response<ErrorDetail>> handleMembershipDownstream(
      AccessMembershipClient.DownstreamUnavailableException ex, HttpServletRequest request) {
    return build(
        ResponseCode.DOWNSTREAM_UNAVAILABLE,
        new ErrorDetail(
            "Downstream unavailable", traceId(request), "DOWNSTREAM_UNAVAILABLE", true));
  }

  @ExceptionHandler(AccessMembershipClient.DownstreamTimeoutException.class)
  public ResponseEntity<Response<ErrorDetail>> handleMembershipDownstreamTimeout(
      AccessMembershipClient.DownstreamTimeoutException ex, HttpServletRequest request) {
    return build(
        ResponseCode.DOWNSTREAM_TIMEOUT,
        new ErrorDetail("Downstream timeout", traceId(request), "DOWNSTREAM_TIMEOUT", true));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<Response<ErrorDetail>> handleIllegalArgument(
      IllegalArgumentException ex, HttpServletRequest request) {
    return build(
        ResponseCode.INVALID_ARGUMENT,
        new ErrorDetail("Invalid argument", traceId(request), "INVALID_ARGUMENT", false));
  }

  // ---- plan_21/21.8 KnowledgeBase / Document 异常映射 ----

  @ExceptionHandler(UploadInvalidException.class)
  public ResponseEntity<Response<ErrorDetail>> handleUploadInvalid(
      UploadInvalidException ex, HttpServletRequest request) {
    // 按 reason 映射稳定错误码；message 不含文件内容
    ResponseCode code =
        switch (ex.getReason()) {
          case TOO_LARGE -> ResponseCode.UPLOAD_TOO_LARGE;
          case UNSUPPORTED_EXTENSION, UNSUPPORTED_MIME -> ResponseCode.UNSUPPORTED_MEDIA_TYPE;
          default -> ResponseCode.VALIDATION_ERROR;
        };
    String reason =
        switch (ex.getReason()) {
          case TOO_LARGE -> "UPLOAD_TOO_LARGE";
          case UNSUPPORTED_EXTENSION, UNSUPPORTED_MIME -> "UNSUPPORTED_MEDIA_TYPE";
          case EMPTY_FILE -> "EMPTY_FILE";
          case NOT_UTF8 -> "NOT_UTF8";
          case MISSING_FILE -> "MISSING_FILE";
          case READ_FAILED -> "UPLOAD_READ_FAILED";
        };
    return build(code, new ErrorDetail("Upload rejected", traceId(request), reason, false));
  }

  @ExceptionHandler(KnowledgeBaseOrchestrator.ForbiddenException.class)
  public ResponseEntity<Response<ErrorDetail>> handleKbForbidden(
      KnowledgeBaseOrchestrator.ForbiddenException ex, HttpServletRequest request) {
    return build(
        ResponseCode.FORBIDDEN, new ErrorDetail("Forbidden", traceId(request), "FORBIDDEN", false));
  }

  @ExceptionHandler(KnowledgeBaseOrchestrator.NotFoundException.class)
  public ResponseEntity<Response<ErrorDetail>> handleKbNotFound(
      KnowledgeBaseOrchestrator.NotFoundException ex, HttpServletRequest request) {
    return build(
        ResponseCode.NOT_FOUND,
        new ErrorDetail("Resource not found", traceId(request), "NOT_FOUND", false));
  }

  @ExceptionHandler(KnowledgeBaseOrchestrator.ConflictException.class)
  public ResponseEntity<Response<ErrorDetail>> handleKbConflict(
      KnowledgeBaseOrchestrator.ConflictException ex, HttpServletRequest request) {
    return build(
        ResponseCode.CONFLICT, new ErrorDetail("Conflict", traceId(request), "CONFLICT", false));
  }

  @ExceptionHandler(
      KnowledgeBaseOrchestrator.EnsureScopeFailedException.DownstreamUnavailableException.class)
  public ResponseEntity<Response<ErrorDetail>> handleKbDownstream(
      KnowledgeBaseOrchestrator.EnsureScopeFailedException.DownstreamUnavailableException ex,
      HttpServletRequest request) {
    return build(
        ResponseCode.DOWNSTREAM_UNAVAILABLE,
        new ErrorDetail(
            "Downstream unavailable", traceId(request), "DOWNSTREAM_UNAVAILABLE", true));
  }

  @ExceptionHandler(
      KnowledgeBaseOrchestrator.EnsureScopeFailedException.DownstreamTimeoutException.class)
  public ResponseEntity<Response<ErrorDetail>> handleKbDownstreamTimeout(
      KnowledgeBaseOrchestrator.EnsureScopeFailedException.DownstreamTimeoutException ex,
      HttpServletRequest request) {
    return build(
        ResponseCode.DOWNSTREAM_TIMEOUT,
        new ErrorDetail("Downstream timeout", traceId(request), "DOWNSTREAM_TIMEOUT", true));
  }

  @ExceptionHandler(KnowledgeDocumentClient.ForbiddenException.class)
  public ResponseEntity<Response<ErrorDetail>> handleDocForbidden(
      KnowledgeDocumentClient.ForbiddenException ex, HttpServletRequest request) {
    return build(
        ResponseCode.FORBIDDEN, new ErrorDetail("Forbidden", traceId(request), "FORBIDDEN", false));
  }

  @ExceptionHandler(KnowledgeDocumentClient.NotFoundException.class)
  public ResponseEntity<Response<ErrorDetail>> handleDocNotFound(
      KnowledgeDocumentClient.NotFoundException ex, HttpServletRequest request) {
    return build(
        ResponseCode.NOT_FOUND,
        new ErrorDetail("Resource not found", traceId(request), "NOT_FOUND", false));
  }

  @ExceptionHandler(KnowledgeDocumentClient.RetryNotAllowedException.class)
  public ResponseEntity<Response<ErrorDetail>> handleRetryNotAllowed(
      KnowledgeDocumentClient.RetryNotAllowedException ex, HttpServletRequest request) {
    return build(
        ResponseCode.INGESTION_RETRY_NOT_ALLOWED,
        new ErrorDetail(
            "Ingestion retry not allowed", traceId(request), "INGESTION_RETRY_NOT_ALLOWED", false));
  }

  @ExceptionHandler(KnowledgeDocumentClient.DownstreamUnavailableException.class)
  public ResponseEntity<Response<ErrorDetail>> handleDocDownstream(
      KnowledgeDocumentClient.DownstreamUnavailableException ex, HttpServletRequest request) {
    return build(
        ResponseCode.DOWNSTREAM_UNAVAILABLE,
        new ErrorDetail(
            "Downstream unavailable", traceId(request), "DOWNSTREAM_UNAVAILABLE", true));
  }

  @ExceptionHandler(KnowledgeDocumentClient.DownstreamTimeoutException.class)
  public ResponseEntity<Response<ErrorDetail>> handleDocDownstreamTimeout(
      KnowledgeDocumentClient.DownstreamTimeoutException ex, HttpServletRequest request) {
    return build(
        ResponseCode.DOWNSTREAM_TIMEOUT,
        new ErrorDetail("Downstream timeout", traceId(request), "DOWNSTREAM_TIMEOUT", true));
  }

  /**
   * Spring multipart resolver 在文件超过配置上限时抛出 MaxUploadSizeExceededException；在 Controller 之前触发。
   *
   * <p>映射为 41301 UPLOAD_TOO_LARGE，保持与 {@link UploadInvalidException}({@link Reason#TOO_LARGE})
   * 一致的错误码。
   */
  @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
  public ResponseEntity<Response<ErrorDetail>> handleMaxUploadSize(
      org.springframework.web.multipart.MaxUploadSizeExceededException ex,
      HttpServletRequest request) {
    return build(
        ResponseCode.UPLOAD_TOO_LARGE,
        new ErrorDetail("Upload too large", traceId(request), "UPLOAD_TOO_LARGE", false));
  }

  @ExceptionHandler(
      org.springframework.web.multipart.support.MissingServletRequestPartException.class)
  public ResponseEntity<Response<ErrorDetail>> handleMissingPart(
      org.springframework.web.multipart.support.MissingServletRequestPartException ex,
      HttpServletRequest request) {
    // 缺少 file 参数 → 视为上传校验失败（MISSING_FILE）
    return build(
        ResponseCode.VALIDATION_ERROR,
        new ErrorDetail("Upload rejected", traceId(request), "MISSING_FILE", false));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Response<ErrorDetail>> handleAny(Exception ex, HttpServletRequest request) {
    log.error("Console 未预期异常 — traceId={}", traceId(request), ex);
    return build(
        ResponseCode.INTERNAL_ERROR,
        new ErrorDetail("Internal server error", traceId(request), "INTERNAL_ERROR", false));
  }

  // ---- plan_21/21.9 API Key 异常映射 ----

  @ExceptionHandler(ApiKeyOrchestrator.ForbiddenException.class)
  public ResponseEntity<Response<ErrorDetail>> handleApiKeyForbidden(
      ApiKeyOrchestrator.ForbiddenException ex, HttpServletRequest request) {
    // MEMBER 越权或 actor 非 OWNER；不泄漏存在性
    return build(
        ResponseCode.FORBIDDEN, new ErrorDetail("Forbidden", traceId(request), "FORBIDDEN", false));
  }

  @ExceptionHandler(ApiKeyOrchestrator.NotFoundException.class)
  public ResponseEntity<Response<ErrorDetail>> handleApiKeyNotFound(
      ApiKeyOrchestrator.NotFoundException ex, HttpServletRequest request) {
    // 跨 KB / Key 不存在统一 not found，不泄漏存在性
    return build(
        ResponseCode.NOT_FOUND,
        new ErrorDetail("Resource not found", traceId(request), "NOT_FOUND", false));
  }

  @ExceptionHandler(ApiKeyOrchestrator.ConflictException.class)
  public ResponseEntity<Response<ErrorDetail>> handleApiKeyConflict(
      ApiKeyOrchestrator.ConflictException ex, HttpServletRequest request) {
    // 状态冲突（disable 已 DISABLED、revoke 已 REVOKED、rotate 非 ACTIVE）→ 409；message 不含完整 Key
    return build(
        ResponseCode.CONFLICT, new ErrorDetail("Conflict", traceId(request), "CONFLICT", false));
  }

  @ExceptionHandler(ApiKeyOrchestrator.DownstreamUnavailableException.class)
  public ResponseEntity<Response<ErrorDetail>> handleApiKeyDownstream(
      ApiKeyOrchestrator.DownstreamUnavailableException ex, HttpServletRequest request) {
    return build(
        ResponseCode.DOWNSTREAM_UNAVAILABLE,
        new ErrorDetail(
            "Downstream unavailable", traceId(request), "DOWNSTREAM_UNAVAILABLE", true));
  }

  @ExceptionHandler(ApiKeyOrchestrator.DownstreamTimeoutException.class)
  public ResponseEntity<Response<ErrorDetail>> handleApiKeyDownstreamTimeout(
      ApiKeyOrchestrator.DownstreamTimeoutException ex, HttpServletRequest request) {
    return build(
        ResponseCode.DOWNSTREAM_TIMEOUT,
        new ErrorDetail("Downstream timeout", traceId(request), "DOWNSTREAM_TIMEOUT", true));
  }

  private String traceId(HttpServletRequest request) {
    Object existing = request.getAttribute("traceId");
    if (existing instanceof String s && !s.isBlank()) {
      return s;
    }
    String header = request.getHeader("X-Request-Id");
    if (header != null && !header.isBlank()) {
      return header;
    }
    return UUID.randomUUID().toString();
  }

  private static String safeMessage(FieldError fe) {
    // Bean Validation 默认消息不含被拒绝原值；显式替换以防框架未来版本回显
    String msg = fe.getDefaultMessage();
    return msg == null ? "invalid" : msg;
  }

  private static Object rejectedValueOrNull(FieldError fe) {
    // 敏感字段（密码/Token/API Key）不回显原值
    String field = fe.getField();
    if (field == null) {
      return null;
    }
    String lower = field.toLowerCase();
    if (lower.contains("password")
        || lower.contains("token")
        || lower.contains("apikey")
        || lower.contains("key")) {
      return null;
    }
    Object v = fe.getRejectedValue();
    return v == null ? null : v.toString();
  }

  private ResponseEntity<Response<ErrorDetail>> build(ResponseCode code, ErrorDetail detail) {
    return ResponseEntity.status(HttpStatus.resolve(code.getHttpStatus().value()))
        .body(Response.error(code, detail));
  }
}
