package ai.cerbur.crag.api.controller.advice;

import org.springframework.http.HttpMethod;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 测试专用 Controller —— 仅在 test source set 中存在，用于触发异常映射分支.
 *
 * <p>生产环境不会暴露这些端点；它们为 GlobalExceptionHandlerComponentTest 提供可控的异常触发路径。
 */
@RestController
@RequestMapping("/api/v1/test/exception")
public class TestExceptionController {

  /** 触发 IllegalArgumentException → INVALID_ARGUMENT / HTTP 400. */
  @GetMapping("/illegal-argument")
  String throwIllegalArgument() {
    throw new IllegalArgumentException("test invalid argument");
  }

  /** 触发未处理 RuntimeException → INTERNAL_ERROR / HTTP 500. */
  @GetMapping("/internal-error")
  String throwInternalError() {
    throw new RuntimeException("test internal error");
  }

  /** 触发 NoResourceFoundException → NOT_FOUND / HTTP 404. */
  @GetMapping("/not-found")
  String throwNotFound() throws NoResourceFoundException {
    throw new NoResourceFoundException(
        HttpMethod.GET, "/api/v1/test/exception/not-found", "test resource not found");
  }
}
