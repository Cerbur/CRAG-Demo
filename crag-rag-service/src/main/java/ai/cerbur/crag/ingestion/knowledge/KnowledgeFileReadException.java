package ai.cerbur.crag.ingestion.knowledge;

/**
 * Knowledge 文件 gRPC 读取失败（Plan 19）.
 *
 * <p>由 {@link KnowledgeDocumentFileClient} 抛出，编排映射为 {@code FILE_READ_FAILED}。消息只含 docId 与 gRPC
 * 状态码级别的安全摘要， 不透传文件内容、storage key 或路径.
 */
public class KnowledgeFileReadException extends RuntimeException {

  public KnowledgeFileReadException(String message) {
    super(message);
  }

  public KnowledgeFileReadException(String message, Throwable cause) {
    super(message, cause);
  }
}
