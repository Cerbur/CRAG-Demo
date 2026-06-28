package ai.cerbur.crag.knowledge.core.ingestion;

/**
 * 手动/自动重试不被允许时抛出（plan_21/21.5）。
 *
 * <p>原因包括：非 FAILED 文档、不可重试失败分类、已达 attempt 上限、文档不存在或 KB 归属不符。 该异常用于驱动 HTTP/gRPC 入口的稳定错误映射（40902
 * INGESTION_RETRY_NOT_ALLOWED），不泄漏内部堆栈。
 */
public class RetryNotAllowedException extends RuntimeException {

  public RetryNotAllowedException(String message) {
    super(message);
  }
}
