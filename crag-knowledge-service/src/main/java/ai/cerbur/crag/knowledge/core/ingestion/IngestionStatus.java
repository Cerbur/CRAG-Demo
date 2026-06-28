package ai.cerbur.crag.knowledge.core.ingestion;

/**
 * Document 在单一 operationVersion 内的摄取状态（plan_21/21.3）。
 *
 * <p>PENDING 为上传后初始态；PROCESSING 为 RAG 正在处理；READY 与 FAILED 为该版本终态。Retry 由 plan_21/21.5 通过递增
 * operationVersion 创建新状态序列实现，本枚举只描述单版本内的合法迁移。
 */
public enum IngestionStatus {
  PENDING,
  PROCESSING,
  READY,
  FAILED;

  /** 按数据库存储值或事件 payload 字符串解析；未知值抛 {@link IllegalArgumentException}。 */
  public static IngestionStatus fromCode(String code) {
    if (code == null) {
      throw new IllegalArgumentException("ingestion status code is null");
    }
    return IngestionStatus.valueOf(code.trim().toUpperCase());
  }
}
