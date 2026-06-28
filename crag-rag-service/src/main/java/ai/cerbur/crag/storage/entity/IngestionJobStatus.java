package ai.cerbur.crag.storage.entity;

/**
 * Ingestion Job 状态机（Plan 19，Plan 21.4 追加 SUPERSEDED）.
 *
 * <p>数据库存储为 SMALLINT，Java 侧用枚举映射：
 *
 * <pre>
 *   0 = PENDING      — DOC_UPLOADED 已落地为 Job，尚未开始处理
 *   1 = PROCESSING   — 正在读取 Knowledge 文件、校验、切分并写入 Chunk
 *   2 = READY        — 处理完成，索引已就绪
 *   3 = FAILED       — 业务处理失败（终态，不自动重试）
 *   4 = SUPERSEDED   — 同 doc 出现更高 operationVersion，旧活动 Job 被标记取代（不参与召回）
 * </pre>
 *
 * <p>合法流转：{@code PENDING → PROCESSING → READY / FAILED}。FAILED 为终态，重复 DOC_UPLOADED 不会自动重跑. {@code
 * SUPERSEDED}（Plan 21.4）仅由 head advance 在更高 operationVersion 接管时写入旧的非终态 Job，使迟到 Worker 无法 READY
 * 一个已被取代的版本；已 READY / FAILED 的 Job 不再被 SUPERSEDED 覆盖.
 */
public enum IngestionJobStatus {
  PENDING(0),
  PROCESSING(1),
  READY(2),
  FAILED(3),
  SUPERSEDED(4);

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
