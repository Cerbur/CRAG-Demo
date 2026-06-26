package ai.cerbur.crag.storage;

/**
 * Ingestion Job CAS 状态推进冲突（Plan 19）.
 *
 * <p>当 {@link IngestionJobDao} 的状态推进 CAS（PENDING → PROCESSING、PROCESSING → READY / FAILED）affected
 * rows 为 0 时抛出，表示 Job 的状态或版本已被其他实例变更。这是消费层幂等场景下的预期并发结果：重复 DOC_UPLOADED 或多个 消费实例同时推进同一 Job
 * 时会发生。调用方按业务语义将其视为「已被推进」，WARN 记录后继续，而非盲目重试.
 */
public class IngestionJobConflictException extends RuntimeException {

  private final long docId;
  private final long operationVersion;

  public IngestionJobConflictException(long docId, long operationVersion, String message) {
    super(message);
    this.docId = docId;
    this.operationVersion = operationVersion;
  }

  public long getDocId() {
    return docId;
  }

  public long getOperationVersion() {
    return operationVersion;
  }
}
