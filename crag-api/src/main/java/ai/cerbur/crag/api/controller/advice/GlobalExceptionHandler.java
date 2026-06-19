package ai.cerbur.crag.api.controller.advice;

import ai.cerbur.crag.common.dto.result.Response;
import ai.cerbur.crag.common.dto.result.ResponseCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理 AOP 层 —— 统一拦截 Controller 层抛出的异常并转换为 Response 格式.
 *
 * <p>Controller 方法无需手动 try/catch：校验异常和业务异常自然上抛至此，由对应 @ExceptionHandler 转为统一格式。 所有异常映射的 HTTP 状态统一从
 * ResponseCode 枚举读取。
 *
 * @since 2026-06-13
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  /**
   * 处理 @Valid 校验失败异常.
   *
   * @param e 校验异常
   * @return ResponseEntity with VALIDATION_ERROR code and HTTP 400
   */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<Response<?>> handleValidation(MethodArgumentNotValidException e) {
    return ResponseEntity.status(ResponseCode.VALIDATION_ERROR.getHttpStatus())
        .body(Response.error(ResponseCode.VALIDATION_ERROR));
  }

  /**
   * 处理程序化参数校验异常.
   *
   * @param e 参数异常
   * @return ResponseEntity with INVALID_ARGUMENT code and HTTP 400
   */
  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<Response<?>> handleBadRequest(IllegalArgumentException e) {
    return ResponseEntity.status(ResponseCode.INVALID_ARGUMENT.getHttpStatus())
        .body(Response.error(ResponseCode.INVALID_ARGUMENT));
  }

  /**
   * 兜底处理所有未预期的内部异常.
   *
   * <p>记录完整堆栈到日志，返回 HTTP 500.
   *
   * @param e 未预期异常
   * @return ResponseEntity with INTERNAL_ERROR code and HTTP 500
   */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<Response<?>> handleInternal(Exception e) {
    log.error("Unhandled exception", e);
    return ResponseEntity.status(ResponseCode.INTERNAL_ERROR.getHttpStatus())
        .body(Response.error(ResponseCode.INTERNAL_ERROR));
  }

  /**
   * 处理 Spring MVC 无资源映射异常（404）.
   *
   * @param e 无资源异常
   * @return ResponseEntity with NOT_FOUND code and HTTP 404
   */
  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<Response<?>> handleNotFound(NoResourceFoundException e) {
    return ResponseEntity.status(ResponseCode.NOT_FOUND.getHttpStatus())
        .body(Response.error(ResponseCode.NOT_FOUND));
  }
}
