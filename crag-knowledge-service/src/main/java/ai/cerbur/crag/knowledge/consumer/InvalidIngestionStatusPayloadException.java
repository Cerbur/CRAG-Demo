package ai.cerbur.crag.knowledge.consumer;

/** INGESTION_* 事件 payload 解析失败异常（plan_21/21.3）。message 不包含字段值，仅描述原因。 */
public class InvalidIngestionStatusPayloadException extends RuntimeException {

  public InvalidIngestionStatusPayloadException(String message) {
    super(message);
  }

  public InvalidIngestionStatusPayloadException(String message, Throwable cause) {
    super(message, cause);
  }
}
