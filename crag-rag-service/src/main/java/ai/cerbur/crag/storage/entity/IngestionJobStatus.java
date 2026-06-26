package ai.cerbur.crag.storage.entity;

/**
 * Ingestion Job 状态机（Plan 19）.
 *
 * <p>数据库存储为 SMALLINT，Java 侧用枚举映射：
 *
 * <pre>
 *   0 = PENDING     — DOC_UPLOADED 已落地为 Job，尚未开始处理
 *   1 = PROCESSING  — 正在读取 Knowledge 文件、校验、切分并写入 Chunk
 *   2 = READY       — 处理完成，索引已就绪
 *   3 = FAILED      — 业务处理失败（终态，不自动重试）
 * </pre>
 *
 * <p>合法流转：{@code PENDING → PROCESSING → READY / FAILED}。FAILED 为终态，重复 DOC_UPLOADED 不会自动重跑.
 */
public enum IngestionJobStatus {
  PENDING(0),
  PROCESSING(1),
  READY(2),
  FAILED(3);

  private final int code;

  IngestionJobStatus(int code) {
    this.code = code;
  }

  /** 数据库 SMALLINT 值. */
  public int getCode() {
    return code;
  }

  /** 根据数据库 SMALLINT 值反查枚举，未匹配时返回 PENDING. */
  public static IngestionJobStatus fromCode(int code) {
    for (IngestionJobStatus s : values()) {
      if (s.code == code) {
        return s;
      }
    }
    return PENDING;
  }
}
