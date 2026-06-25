package ai.cerbur.crag.knowledge.controller.smoke;

import ai.cerbur.crag.common.dto.result.Response;
import ai.cerbur.crag.common.dto.result.ResponseCode;
import ai.cerbur.crag.knowledge.core.knowledgebase.KnowledgeBaseNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * smoke HTTP 异常映射，仅 {@code smoke} Profile 生效。
 *
 * <p>校验类非法参数映射为 400；知识库不存在映射为 404；其余兜底为 500。不泄漏堆栈、SQL 或内部路径。
 */
@Profile("smoke")
@RestControllerAdvice
public class KnowledgeSmokeExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(KnowledgeSmokeExceptionHandler.class);

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<Response<?>> handleValidation(MethodArgumentNotValidException e) {
    return ResponseEntity.status(ResponseCode.VALIDATION_ERROR.getHttpStatus())
        .body(Response.error(ResponseCode.VALIDATION_ERROR));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<Response<?>> handleInvalidArgument(IllegalArgumentException e) {
    return ResponseEntity.status(ResponseCode.INVALID_ARGUMENT.getHttpStatus())
        .body(Response.error(ResponseCode.INVALID_ARGUMENT));
  }

  @ExceptionHandler(KnowledgeBaseNotFoundException.class)
  public ResponseEntity<Response<?>> handleKnowledgeBaseNotFound(KnowledgeBaseNotFoundException e) {
    return ResponseEntity.status(ResponseCode.NOT_FOUND.getHttpStatus())
        .body(Response.error(ResponseCode.NOT_FOUND));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Response<?>> handleInternal(Exception e) {
    log.error("Unhandled smoke exception", e);
    return ResponseEntity.status(ResponseCode.INTERNAL_ERROR.getHttpStatus())
        .body(Response.error(ResponseCode.INTERNAL_ERROR));
  }
}
