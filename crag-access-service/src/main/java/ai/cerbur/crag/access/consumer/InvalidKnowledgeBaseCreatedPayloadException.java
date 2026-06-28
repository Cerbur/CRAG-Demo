package ai.cerbur.crag.access.consumer;

/**
 * {@code KNOWLEDGE_BASE_CREATED} payload 解析失败（plan_21/21.2）。
 *
 * <p>由 {@link KnowledgeBaseCreatedEventHandler} 捕获并映射为 nonRetryableFailure（安全 DLQ）；消息不泄漏字段值。
 */
public class InvalidKnowledgeBaseCreatedPayloadException extends RuntimeException {
  public InvalidKnowledgeBaseCreatedPayloadException(String message) {
    super(message);
  }

  public InvalidKnowledgeBaseCreatedPayloadException(String message, Throwable cause) {
    super(message, cause);
  }
}
