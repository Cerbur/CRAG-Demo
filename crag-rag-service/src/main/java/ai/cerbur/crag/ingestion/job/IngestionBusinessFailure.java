package ai.cerbur.crag.ingestion.job;

/**
 * Ingestion 业务处理失败（Plan 19）—— 携带安全失败分类与短摘要，由编排捕获后推进 Job 为 FAILED.
 *
 * <p>消息须为安全短摘要，不透传 SQL、堆栈、文件内容、storage key 或路径.
 */
public class IngestionBusinessFailure extends RuntimeException {

  private final IngestionJobFailureCategory category;

  public IngestionBusinessFailure(IngestionJobFailureCategory category, String message) {
    super(message);
    this.category = category;
  }

  public IngestionJobFailureCategory getCategory() {
    return category;
  }
}
