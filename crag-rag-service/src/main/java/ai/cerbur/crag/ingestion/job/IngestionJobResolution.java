package ai.cerbur.crag.ingestion.job;

import ai.cerbur.crag.storage.entity.IngestionJob;
import java.util.Objects;

/**
 * Ingestion Job 解析结果（Plan 19）—— 消费 {@code DOC_UPLOADED} 后，幂等创建或定位 Job，并给出是否需要继续处理的决策.
 *
 * <p>{@link #fresh()} 表示本次创建了新 Job；{@link #needsProcessing()} 表示 Job 仍处于 {@code PENDING}，需要推进为
 * {@code PROCESSING} 并执行文件读取/切分。重复事件命中已有 {@code READY} / {@code FAILED} 时，两者均为 false，消费层直接视为
 * 已处理，不重复建 Job 或写 Chunk.
 *
 * @param job 持久化的 Job
 * @param fresh 本次是否新建了 Job（false = 命中已有业务键）
 * @param needsProcessing Job 是否仍为 PENDING，需要继续处理
 */
public record IngestionJobResolution(IngestionJob job, boolean fresh, boolean needsProcessing) {

  public IngestionJobResolution {
    Objects.requireNonNull(job, "job");
  }
}
