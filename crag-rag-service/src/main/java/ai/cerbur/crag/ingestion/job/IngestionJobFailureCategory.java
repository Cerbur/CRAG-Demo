package ai.cerbur.crag.ingestion.job;

/**
 * Ingestion Job 业务失败分类（Plan 19）.
 *
 * <p>用于 Job 进入 {@code FAILED} 终态时记录安全分类，并供 {@code INGESTION_FAILED} 状态事件 payload 携带。枚举名是稳定契约， 不透传
 * SQL、堆栈、文件内容、storage key 或下游原始敏感错误.
 */
public enum IngestionJobFailureCategory {
  /** 兜底分类：未归类或尚未明确的失败. */
  UNKNOWN,
  /** Knowledge gRPC 读取文件失败或返回空. */
  FILE_READ_FAILED,
  /** 文件 sha256 与 DOC_UPLOADED payload 不一致. */
  FILE_CHECKSUM_MISMATCH,
  /** 文件字节数与 DOC_UPLOADED payload 不一致. */
  FILE_SIZE_MISMATCH,
  /** 文件类型不在支持范围（TXT / MARKDOWN）. */
  FILE_TYPE_UNSUPPORTED,
  /** 文件无法按 UTF-8 解码. */
  FILE_DECODE_FAILED,
  /** 文本切分失败. */
  CHUNK_SPLIT_FAILED
}
