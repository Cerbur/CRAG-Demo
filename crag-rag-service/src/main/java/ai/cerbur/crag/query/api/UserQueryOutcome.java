package ai.cerbur.crag.query.api;

import ai.cerbur.crag.retrieval.api.result.ParentEvidenceResult;
import java.util.List;

/**
 * Query 完整产出（Plan 21.4）—— 同时携带 {@link UserQueryResult} 与对应 parent evidence，供 gRPC Provider 映射
 * Citation（reference/documentId/excerpt）且不重复检索.
 *
 * <p>evidence 与 {@code result.sources()} 按 {@code parentChunkId} 对齐：Provider 只取 sources 中包含的
 * parent， 用其 docId 与截断后的 content 组装 Citation.
 *
 * @param result 用户查询结果（answer + 连续引用 sources）
 * @param evidence 进入 Context 的 parent evidence（与 sources 一一对应，顺序与 sources 一致）
 */
public record UserQueryOutcome(UserQueryResult result, List<ParentEvidenceResult> evidence) {

  public UserQueryOutcome {
    if (result == null) {
      throw new IllegalArgumentException("result must not be null");
    }
    if (evidence == null) {
      throw new IllegalArgumentException("evidence must not be null");
    }
    evidence = List.copyOf(evidence);
  }
}
