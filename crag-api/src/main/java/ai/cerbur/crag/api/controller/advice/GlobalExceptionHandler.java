package ai.cerbur.crag.api.controller.advice;

import ai.cerbur.crag.common.dto.result.Response;
import ai.cerbur.crag.common.dto.result.ResponseCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理 AOP 层 —— 统一拦截 Controller 层抛出的异常并转换为 Response 格式.
 *
 * <p>Controller 方法无需手动 try/catch：校验异常和业务异常自然上抛至此，由对应 @ExceptionHandler 转为统一格式.
 *
 * @since 2026-06-13
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  /**
   * 处理 @Valid 校验失败异常.
   *
   * <p>当 AdminRagRequest 的 @NotBlank 等校验不通过时，Spring 自动抛出此异常.
   *
   * @param e 校验异常
   * @return Response with success=false, code=400
   */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public Response<?> handleValidation(MethodArgumentNotValidException e) {
    return Response.error(ResponseCode.BAD_REQUEST);
  }

  /**
   * 处理程序化参数校验异常.
   *
   * <p>Controller 或 Service 层主动抛出的 IllegalArgumentException 在此捕获.
   *
   * @param e 参数异常
   * @return Response with success=false, code=400
   */
  @ExceptionHandler(IllegalArgumentException.class)
  public Response<?> handleBadRequest(IllegalArgumentException e) {
    return Response.error(ResponseCode.BAD_REQUEST);
  }

  /**
   * 兜底处理所有未预期的内部异常.
   *
   * <p>记录完整堆栈到日志，返回 HTTP 500.
   *
   * @param e 未预期异常
   * @return ResponseEntity with HTTP 500 and body code=INTERNAL_ERROR
   */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<Response<?>> handleInternal(Exception e) {
    log.error("Unhandled exception", e);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(Response.error(ResponseCode.INTERNAL_ERROR));
  }

  /**
   * 处理 Spring MVC 无资源映射异常（404）.
   *
   * <p>当请求路径无对应 Handler 时 Spring 抛出此异常。显式返回 HTTP 404， 避免被兜底 handler 转为 500。
   *
   * @param e 无资源异常
   * @return ResponseEntity with HTTP 404 and body code=NOT_FOUND
   */
  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<Response<?>> handleNotFound(NoResourceFoundException e) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Response.error(ResponseCode.NOT_FOUND));
  }
}
