package ai.cerbur.crag.rag.grpc.mapper;

import ai.cerbur.crag.contracts.rag.v1.Citation;
import ai.cerbur.crag.contracts.rag.v1.QueryResponse;
import ai.cerbur.crag.query.api.QuerySource;
import ai.cerbur.crag.query.api.UserQueryResult;
import ai.cerbur.crag.retrieval.api.result.ParentEvidenceResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将 Query 领域结果映射为 gRPC {@link QueryResponse}（Plan 21.4）.
 *
 * <p>Citation 只暴露 {@code reference / documentId / excerpt}，不泄漏 chunkId、score 或 Context. {@code
 * reference} 与 {@code UserQueryResult.sources()} 的连续编号对应；{@code documentId} 来自 {@link
 * ParentEvidenceResult#docId()}； {@code excerpt} 取 parent evidence 完整 content 并防御截断到 500 个 Unicode
 * 字符.
 */
public final class RagQueryMapper {

  /** Citation excerpt 最大 Unicode 字符数（Plan 21.4，全局约束）. */
  public static final int MAX_EXCERPT_CHARS = 500;

  private RagQueryMapper() {}

  /**
   * 映射 {@link UserQueryResult} + 关联 evidence 到 {@link QueryResponse}.
   *
   * @param result Query 领域结果
   * @param evidence 已按 sources 顺序对齐的 parent evidence（供 excerpt 与 documentId 提取）
   * @return gRPC 响应
   */
  public static QueryResponse toProto(UserQueryResult result, List<ParentEvidenceResult> evidence) {
    Map<Long, ParentEvidenceResult> byParent = new LinkedHashMap<>();
    for (ParentEvidenceResult pe : evidence) {
      byParent.putIfAbsent(pe.parentChunkId(), pe);
    }
    List<Citation> citations = new ArrayList<>();
    for (QuerySource source : result.sources()) {
      ParentEvidenceResult pe = byParent.get(source.parentChunkId());
      if (pe == null) {
        continue;
      }
      citations.add(
          Citation.newBuilder()
              .setReference(source.reference())
              .setDocumentId(Long.toString(pe.docId()))
              .setExcerpt(truncateExcerpt(pe.content()))
              .build());
    }
    return QueryResponse.newBuilder().setAnswer(result.answer()).addAllSources(citations).build();
  }

  /**
   * 防御截断 excerpt 到 {@link #MAX_EXCERPT_CHARS} 个 Unicode 字符.
   *
   * @param content 原始 parent evidence content
   * @return 截断后的 excerpt
   */
  public static String truncateExcerpt(String content) {
    if (content == null) {
      return "";
    }
    return content.length() <= MAX_EXCERPT_CHARS
        ? content
        : content.substring(0, MAX_EXCERPT_CHARS);
  }
}
