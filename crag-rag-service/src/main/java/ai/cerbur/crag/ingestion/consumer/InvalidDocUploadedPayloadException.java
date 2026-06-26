package ai.cerbur.crag.ingestion.consumer;

/**
 * {@code DOC_UPLOADED} payload 解析或校验失败（Plan 19）.
 *
 * <p>consumer 将其映射为安全失败路径（non-retryable），避免对永久畸形事件无限重试。消息只描述字段级原因，不透传文件内容、 storage key 或路径——payload
 * 本身不含这些字段.
 */
public class InvalidDocUploadedPayloadException extends RuntimeException {

  public InvalidDocUploadedPayloadException(String message) {
    super(message);
  }

  public InvalidDocUploadedPayloadException(String message, Throwable cause) {
    super(message, cause);
  }
}
