package ai.cerbur.crag.ingestion.head;

/**
 * head advance 推进分类（Plan 21.4）.
 *
 * <ul>
 *   <li>{@link #ADVANCED} — 本事件成功把 head 推进到更高 operationVersion，旧活动 Job 已标记 SUPERSEDED；
 *   <li>{@link #EQUAL_VERSION_ACK} — 本事件 operationVersion 等于当前 head，幂等 ACK，调用方可继续 Job 编排；
 *   <li>{@link #LOW_VERSION_ACK} — 本事件 operationVersion 低于当前 head，幂等 ACK，调用方不应继续处理（旧/重复事件）。
 * </ul>
 */
public enum HeadAdvanceOutcome {
  ADVANCED,
  EQUAL_VERSION_ACK,
  LOW_VERSION_ACK
}
