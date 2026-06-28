package ai.cerbur.crag.rag.grpc.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import ai.cerbur.crag.contracts.rag.v1.QueryResponse;
import ai.cerbur.crag.query.api.QuerySource;
import ai.cerbur.crag.query.api.UserQueryResult;
import ai.cerbur.crag.retrieval.api.result.ParentEvidenceResult;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * RagQueryMapper 单元测试（Plan 21.4）：验证 Citation 只暴露 reference/documentId/excerpt，excerpt 截断到 500
 * Unicode 字符，引用连续（S1..Sn）.
 */
@DisplayName("RagQueryMapper Citation 映射与 excerpt 截断")
class RagQueryMapperTest {

  @Nested
  @DisplayName("Citation 映射")
  class CitationMapping {

    @Test
    @DisplayName("正常映射：reference 连续 S1/S2，documentId 十进制，excerpt 来自 parent content")
    void mapsReferenceDocumentIdAndExcerpt() {
      UserQueryResult result =
          new UserQueryResult(
              "answer",
              List.of(
                  new QuerySource("S1", 100L, List.of(1001L)),
                  new QuerySource("S2", 200L, List.of(2001L))));
      List<ParentEvidenceResult> evidence =
          List.of(
              new ParentEvidenceResult(100L, 5001L, "parent one content", List.of(1001L)),
              new ParentEvidenceResult(200L, 5002L, "parent two content", List.of(2001L)));

      QueryResponse response = RagQueryMapper.toProto(result, evidence);

      assertThat(response.getAnswer()).isEqualTo("answer");
      assertThat(response.getSourcesCount()).isEqualTo(2);
      assertThat(response.getSources(0).getReference()).isEqualTo("S1");
      assertThat(response.getSources(0).getDocumentId()).isEqualTo("5001");
      assertThat(response.getSources(0).getExcerpt()).isEqualTo("parent one content");
      assertThat(response.getSources(1).getReference()).isEqualTo("S2");
      assertThat(response.getSources(1).getDocumentId()).isEqualTo("5002");
    }

    @Test
    @DisplayName("空 sources → 空 Citation 列表，answer 保留")
    void emptySourcesProducesNoCitations() {
      UserQueryResult result = new UserQueryResult("insufficient evidence", List.of());

      QueryResponse response = RagQueryMapper.toProto(result, List.of());

      assertThat(response.getSourcesList()).isEmpty();
      assertThat(response.getAnswer()).isEqualTo("insufficient evidence");
    }

    @Test
    @DisplayName("source 无对应 evidence → 该 source 跳过，不产生 Citation")
    void sourceWithoutEvidenceIsSkipped() {
      UserQueryResult result =
          new UserQueryResult("answer", List.of(new QuerySource("S1", 999L, List.of(1L))));

      QueryResponse response = RagQueryMapper.toProto(result, List.of());

      assertThat(response.getSourcesList()).isEmpty();
    }
  }

  @Nested
  @DisplayName("excerpt 500 字符防御截断")
  class ExcerptTruncation {

    @Test
    @DisplayName("excerpt ≤ 500 字符 → 不截断")
    void shortExcerptNotTruncated() {
      String content = "a".repeat(500);

      String excerpt = RagQueryMapper.truncateExcerpt(content);

      assertThat(excerpt).hasSize(500);
    }

    @Test
    @DisplayName("excerpt > 500 字符 → 截断到 500")
    void longExcerptTruncated() {
      String content = "b".repeat(800);

      String excerpt = RagQueryMapper.truncateExcerpt(content);

      assertThat(excerpt).hasSize(500);
    }

    @Test
    @DisplayName("excerpt 超长 → 映射后 Citation excerpt 恰好 500")
    void longContentProduces500CharCitation() {
      String longContent = "c".repeat(1200);
      UserQueryResult result =
          new UserQueryResult("answer", List.of(new QuerySource("S1", 100L, List.of(1L))));
      List<ParentEvidenceResult> evidence =
          List.of(new ParentEvidenceResult(100L, 5001L, longContent, List.of(1L)));

      QueryResponse response = RagQueryMapper.toProto(result, evidence);

      assertThat(response.getSources(0).getExcerpt()).hasSize(500);
    }

    @Test
    @DisplayName("中文 excerpt > 500 Unicode 字符 → 按 Unicode code point 截断到 500")
    void cjkLongExcerptTruncatedByCodeUnit() {
      String longContent = "中".repeat(700);

      String excerpt = RagQueryMapper.truncateExcerpt(longContent);

      assertThat(excerpt).hasSize(500);
      assertThat(excerpt).matches("中+");
    }

    @Test
    @DisplayName("null content → 空 excerpt")
    void nullContentReturnsEmpty() {
      assertThat(RagQueryMapper.truncateExcerpt(null)).isEmpty();
    }
  }

  @Test
  @DisplayName("引用连续性：S1..Sn 顺序由 UserQueryResult 保证，Citation 顺序一致")
  void citationsPreserveSourceOrder() {
    List<QuerySource> sources =
        IntStream.rangeClosed(1, 3)
            .mapToObj(i -> new QuerySource("S" + i, (long) (i * 100), List.of((long) (i * 1000))))
            .toList();
    UserQueryResult result = new UserQueryResult("answer", sources);
    List<ParentEvidenceResult> evidence =
        IntStream.rangeClosed(1, 3)
            .mapToObj(
                i ->
                    new ParentEvidenceResult(
                        (long) (i * 100), (long) (i * 5000), "p" + i, List.of((long) (i * 1000))))
            .toList();

    QueryResponse response = RagQueryMapper.toProto(result, evidence);

    assertThat(response.getSourcesList())
        .extracting(c -> c.getReference())
        .containsExactly("S1", "S2", "S3");
  }
}
