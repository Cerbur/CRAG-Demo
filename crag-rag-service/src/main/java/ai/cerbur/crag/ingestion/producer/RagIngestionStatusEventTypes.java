package ai.cerbur.crag.ingestion.producer;

/** RAG ingestion 状态事件类型与生产者常量（Plan 19）. */
public final class RagIngestionStatusEventTypes {

  private RagIngestionStatusEventTypes() {}

  /** Job 进入 PROCESSING. */
  public static final String INGESTION_PROCESSING = "INGESTION_PROCESSING";

  /** Job 进入 READY（索引就绪）. */
  public static final String INGESTION_READY = "INGESTION_READY";

  /** Job 进入 FAILED（业务失败终态）. */
  public static final String INGESTION_FAILED = "INGESTION_FAILED";

  /** 事件生产者标识. */
  public static final String PRODUCER = "rag-service";

  /** 资源类型：以文档为状态回传资源. */
  public static final String RESOURCE_DOCUMENT = "DOCUMENT";

  /** payload 结构版本. */
  public static final int PAYLOAD_VERSION = 1;
}
