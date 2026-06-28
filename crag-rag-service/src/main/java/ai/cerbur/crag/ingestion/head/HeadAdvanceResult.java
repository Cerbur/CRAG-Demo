package ai.cerbur.crag.ingestion.head;

import ai.cerbur.crag.storage.result.IngestionHead;
import java.util.Objects;

/**
 * head advance 结果（Plan 21.4）.
 *
 * @param head 推进后的当前 head 投影
 * @param shouldProcess 本次事件是否应继续 Job 编排（true = 新版本或等版本可继续；false = 旧版本或已被并发更高版本取代）
 * @param outcome 推进分类
 */
public record HeadAdvanceResult(
    IngestionHead head, boolean shouldProcess, HeadAdvanceOutcome outcome) {

  public HeadAdvanceResult {
    Objects.requireNonNull(head, "head");
    Objects.requireNonNull(outcome, "outcome");
  }
}
