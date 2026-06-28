package ai.cerbur.crag.console.advice;

import ai.cerbur.crag.common.dto.error.ErrorDetail;
import ai.cerbur.crag.common.dto.error.FieldErrorDetail;
import ai.cerbur.crag.common.dto.result.Response;
import ai.cerbur.crag.common.dto.result.ResponseCode;
import ai.cerbur.crag.console.auth.service.AccessIdentityClient.DownstreamTimeoutException;
import ai.cerbur.crag.console.auth.service.AccessIdentityClient.DownstreamUnavailableException;
import ai.cerbur.crag.console.auth.service.AccessIdentityClient.InvalidCredentialsException;
import ai.cerbur.crag.console.auth.service.InvalidOriginException;
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

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Response<ErrorDetail>> handleAny(Exception ex, HttpServletRequest request) {
    log.error("Console 未预期异常 — traceId={}", traceId(request), ex);
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
