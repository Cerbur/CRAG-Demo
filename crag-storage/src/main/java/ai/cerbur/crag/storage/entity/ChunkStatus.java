package ai.cerbur.crag.storage.entity;

/**
 * Chunk 异步处理状态 —— Dense 和 Sparse 两条链路共用.
 *
 * <p>数据库存储为 SMALLINT，Java 侧用枚举映射:
 *
 * <pre>
 *   0 = INIT       — 刚分块完成，等待 Cron 处理
 *   1 = PROCESSING — Cron 正在处理中
 *   2 = SUCCESS    — 处理完成
 *   3 = FAILED     — 处理失败，Cron 可重试
 *   4 = SKIPPED    — 跳过（parent chunk 无需此链路）
 * </pre>
 *
 * @since 2026-06-10
 */
public enum ChunkStatus {
  INIT(0),
  PROCESSING(1),
  SUCCESS(2),
  FAILED(3),
  SKIPPED(4);

  private final int code;

  ChunkStatus(int code) {
    this.code = code;
  }

  public int getCode() {
    return code;
  }

  /**
   * 根据数据库 SMALLINT 值反查枚举.
   *
   * @param code 数据库存储的整数值
   * @return 对应枚举，未匹配时返回 INIT
   */
  public static ChunkStatus fromCode(int code) {
    for (ChunkStatus s : values()) {
      if (s.code == code) {
        return s;
      }
    }
    return INIT;
  }
}
