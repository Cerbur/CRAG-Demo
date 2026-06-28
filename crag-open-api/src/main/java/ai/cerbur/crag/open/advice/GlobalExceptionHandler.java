package ai.cerbur.crag.open.advice;

import ai.cerbur.crag.common.dto.error.ErrorDetail;
import ai.cerbur.crag.common.dto.error.FieldErrorDetail;
import ai.cerbur.crag.common.dto.result.Response;
import ai.cerbur.crag.common.dto.result.ResponseCode;
import ai.cerbur.crag.open.auth.service.AccessApiKeyClient;
import ai.cerbur.crag.open.auth.service.BearerApiKeyExtractor.MissingApiKeyException;
import ai.cerbur.crag.open.query.service.RagQueryClient;
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
 * Open 统一异常映射（plan_21/21.10）。
 *
 * <p>共享 crag-common 的 {@link ErrorDetail}/{@link ResponseCode}。鉴权失败不泄漏原因；LLM 不可用映射为 50201。 生成
 * traceId 写入响应。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<Response<ErrorDetail>> handleValidation(
      MethodArgumentNotValidException ex, HttpServletRequest request) {
    List<FieldErrorDetail> fields = new ArrayList<>();
    for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
      // 敏感字段（question 可能含敏感内容）不回显原值
      fields.add(new FieldErrorDetail(fe.getField(), safeMessage(fe), null));
    }
    return build(
        ResponseCode.VALIDATION_ERROR,
        new ErrorDetail("Validation failed", traceId(request), "VALIDATION_ERROR", false, fields));
  }

  @ExceptionHandler(MissingApiKeyException.class)
  public ResponseEntity<Response<ErrorDetail>> handleMissingApiKey(
      MissingApiKeyException ex, HttpServletRequest request) {
    return build(
        ResponseCode.UNAUTHENTICATED,
        new ErrorDetail("Unauthenticated", traceId(request), "MISSING_API_KEY", false));
  }

  @ExceptionHandler(AccessApiKeyClient.InvalidApiKeyException.class)
  public ResponseEntity<Response<ErrorDetail>> handleInvalidApiKey(
      AccessApiKeyClient.InvalidApiKeyException ex, HttpServletRequest request) {
    // 不泄漏鉴权失败原因
    return build(
        ResponseCode.INVALID_CREDENTIALS,
        new ErrorDetail("Authentication failed", traceId(request), "INVALID_API_KEY", false));
  }

  @ExceptionHandler(RagQueryClient.InvalidQueryException.class)
  public ResponseEntity<Response<ErrorDetail>> handleInvalidQuery(
      RagQueryClient.InvalidQueryException ex, HttpServletRequest request) {
    return build(
        ResponseCode.INVALID_ARGUMENT,
        new ErrorDetail("Invalid argument", traceId(request), "INVALID_QUERY", false));
  }

  @ExceptionHandler(RagQueryClient.KnowledgeBaseNotFoundException.class)
  public ResponseEntity<Response<ErrorDetail>> handleKbNotFound(
      RagQueryClient.KnowledgeBaseNotFoundException ex, HttpServletRequest request) {
    return build(
        ResponseCode.NOT_FOUND,
        new ErrorDetail("Resource not found", traceId(request), "NOT_FOUND", false));
  }

  @ExceptionHandler(RagQueryClient.LlmUnavailableException.class)
  public ResponseEntity<Response<ErrorDetail>> handleLlmUnavailable(
      RagQueryClient.LlmUnavailableException ex, HttpServletRequest request) {
    return build(
        ResponseCode.LLM_UNAVAILABLE,
        new ErrorDetail("LLM unavailable", traceId(request), "LLM_UNAVAILABLE", true));
  }

  @ExceptionHandler(AccessApiKeyClient.DownstreamUnavailableException.class)
  public ResponseEntity<Response<ErrorDetail>> handleAccessDownstream(
      AccessApiKeyClient.DownstreamUnavailableException ex, HttpServletRequest request) {
    return build(
        ResponseCode.DOWNSTREAM_UNAVAILABLE,
        new ErrorDetail(
            "Downstream unavailable", traceId(request), "DOWNSTREAM_UNAVAILABLE", true));
  }

  @ExceptionHandler(AccessApiKeyClient.DownstreamTimeoutException.class)
  public ResponseEntity<Response<ErrorDetail>> handleAccessTimeout(
      AccessApiKeyClient.DownstreamTimeoutException ex, HttpServletRequest request) {
    return build(
        ResponseCode.DOWNSTREAM_TIMEOUT,
        new ErrorDetail("Downstream timeout", traceId(request), "DOWNSTREAM_TIMEOUT", true));
  }

  @ExceptionHandler(RagQueryClient.DownstreamUnavailableException.class)
  public ResponseEntity<Response<ErrorDetail>> handleRagDownstream(
      RagQueryClient.DownstreamUnavailableException ex, HttpServletRequest request) {
    return build(
        ResponseCode.DOWNSTREAM_UNAVAILABLE,
        new ErrorDetail(
            "Downstream unavailable", traceId(request), "DOWNSTREAM_UNAVAILABLE", true));
  }

  @ExceptionHandler(RagQueryClient.DownstreamTimeoutException.class)
  public ResponseEntity<Response<ErrorDetail>> handleRagTimeout(
      RagQueryClient.DownstreamTimeoutException ex, HttpServletRequest request) {
    return build(
        ResponseCode.DOWNSTREAM_TIMEOUT,
        new ErrorDetail("Downstream timeout", traceId(request), "DOWNSTREAM_TIMEOUT", true));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Response<ErrorDetail>> handleAny(Exception ex, HttpServletRequest request) {
    log.error("Open 未预期异常 — traceId={}", traceId(request), ex);
    return build(
        ResponseCode.INTERNAL_ERROR,
        new ErrorDetail("Internal server error", traceId(request), "INTERNAL_ERROR", false));
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
    String msg = fe.getDefaultMessage();
    return msg == null ? "invalid" : msg;
  }

  private ResponseEntity<Response<ErrorDetail>> build(ResponseCode code, ErrorDetail detail) {
    return ResponseEntity.status(HttpStatus.resolve(code.getHttpStatus().value()))
        .body(Response.error(code, detail));
  }
}
