package ai.cerbur.crag.retrieval.api.embedding;

/**
 * Embedding 调用异常 —— 当 Sidecar /embed 端点调用失败时抛出.
 *
 * <p>上层 Cron（EmbeddingService）捕获此异常后将 chunk dense_status 标记为 FAILED， 下轮 Cron 通过 T2
 * 状态转换自动重试。消息中保留原始错误信息便于日志排查.
 *
 * @since 2026-06-13
 */
public class EmbeddingException extends RuntimeException {

  /**
   * 构造带详细消息的异常.
   *
   * @param message 错误描述，包含 chunk 上下文和原始错误
   */
  public EmbeddingException(String message) {
    super(message);
  }

  /**
   * 构造带详细消息和原始异常的异常.
   *
   * @param message 错误描述
   * @param cause 原始异常（HTTP 错误、超时等）
   */
  public EmbeddingException(String message, Throwable cause) {
    super(message, cause);
  }
}
