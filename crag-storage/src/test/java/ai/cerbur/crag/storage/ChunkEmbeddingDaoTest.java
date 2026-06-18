package ai.cerbur.crag.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.cerbur.crag.storage.repository.ChunkEmbeddingRepository;
import ai.cerbur.crag.storage.result.DenseSearchResult;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * ChunkEmbeddingDao 单元测试 —— 验证 searchSimilar 的向量保护、格式转换、列映射到 DenseSearchResult.
 *
 * <p>Repository 层通过 Mockito 隔离，聚焦 Dao 层的业务判断逻辑和 Object[] → DenseSearchResult 映射.
 *
 * @since 2026-06-15
 */
@DisplayName("ChunkEmbeddingDao 向量相似度检索")
@ExtendWith(MockitoExtension.class)
class ChunkEmbeddingDaoTest {

  @Mock private ChunkEmbeddingRepository chunkEmbeddingRepository;

  @InjectMocks private ChunkEmbeddingDao chunkEmbeddingDao;

  @Nested
  @DisplayName("空向量/无效输入保护")
  class EmptyVectorProtection {

    @Test
    @DisplayName("vector 为 null → 返回空列表，不调用 Repository")
    void nullVectorReturnsEmpty() {
      List<DenseSearchResult> results = chunkEmbeddingDao.searchSimilar(null, 10);

      assertThat(results).isEmpty();
      verifyNoInteractions(chunkEmbeddingRepository);
    }

    @Test
    @DisplayName("vector 长度为 0 → 返回空列表，不调用 Repository")
    void emptyVectorReturnsEmpty() {
      List<DenseSearchResult> results = chunkEmbeddingDao.searchSimilar(new float[0], 10);

      assertThat(results).isEmpty();
      verifyNoInteractions(chunkEmbeddingRepository);
    }
  }

  @Nested
  @DisplayName("列映射正确性")
  class ColumnMapping {

    private float[] vector;

    @BeforeEach
    void setUp() {
      vector = new float[] {0.1f, 0.2f, 0.3f};
    }

    @Test
    @DisplayName("Repository 返回空列表 → searchSimilar 返回空列表")
    void emptyRepositoryResultReturnsEmpty() {
      when(chunkEmbeddingRepository.searchSimilar(anyString(), anyInt()))
          .thenReturn(Collections.emptyList());

      List<DenseSearchResult> results = chunkEmbeddingDao.searchSimilar(vector, 5);

      assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("单条结果 → chunkId/parentChunkId/chunkIndex/score/content 正确映射")
    void singleRowMapsCorrectly() {
      Object[] row = {"chunk-001", "parent-001", 2, 0.85, "这是匹配的内容"};
      when(chunkEmbeddingRepository.searchSimilar(anyString(), anyInt()))
          .thenReturn(List.<Object[]>of(row));

      List<DenseSearchResult> results = chunkEmbeddingDao.searchSimilar(vector, 3);

      assertThat(results).hasSize(1);
      DenseSearchResult r = results.get(0);
      assertThat(r.getChunkId()).isEqualTo("chunk-001");
      assertThat(r.getParentChunkId()).isEqualTo("parent-001");
      assertThat(r.getChunkIndex()).isEqualTo(2);
      assertThat(r.getDenseScore()).isEqualTo(0.85);
      assertThat(r.getContent()).isEqualTo("这是匹配的内容");
    }

    @Test
    @DisplayName("多条结果 → 按 Repository 返回顺序映射，数量一致")
    void multipleRowsMapCorrectly() {
      Object[] row1 = {"c1", "p1", 0, 0.95, "内容一"};
      Object[] row2 = {"c2", "p2", 1, 0.80, "内容二"};
      Object[] row3 = {"c3", "p3", 2, 0.60, "内容三"};
      when(chunkEmbeddingRepository.searchSimilar(anyString(), anyInt()))
          .thenReturn(List.<Object[]>of(row1, row2, row3));

      List<DenseSearchResult> results = chunkEmbeddingDao.searchSimilar(vector, 10);

      assertThat(results).hasSize(3);
      assertThat(results.get(0).getChunkId()).isEqualTo("c1");
      assertThat(results.get(1).getChunkId()).isEqualTo("c2");
      assertThat(results.get(2).getChunkId()).isEqualTo("c3");
    }

    @Test
    @DisplayName("score 为整数类型（如 Integer）→ doubleValue() 转换正确")
    void integerScoreConvertsToDouble() {
      Object[] row = {"c1", "p1", 0, 1, "内容"};
      when(chunkEmbeddingRepository.searchSimilar(anyString(), anyInt()))
          .thenReturn(List.<Object[]>of(row));

      List<DenseSearchResult> results = chunkEmbeddingDao.searchSimilar(vector, 1);

      assertThat(results).hasSize(1);
      assertThat(results.get(0).getDenseScore()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("score 为 Long 类型 → doubleValue() 转换正确")
    void longScoreConvertsToDouble() {
      Object[] row = {"c1", "p1", 0, 0L, "内容"};
      when(chunkEmbeddingRepository.searchSimilar(anyString(), anyInt()))
          .thenReturn(List.<Object[]>of(row));

      List<DenseSearchResult> results = chunkEmbeddingDao.searchSimilar(vector, 1);

      assertThat(results).hasSize(1);
      assertThat(results.get(0).getDenseScore()).isEqualTo(0.0);
    }
  }

  @Nested
  @DisplayName("pgvector 格式转换与参数传递")
  class PgvectorFormat {

    @Test
    @DisplayName("float[] → pgvector 字面量格式正确（紧凑带方括号）")
    void vectorFormatIsCompactWithBrackets() {
      float[] vector = {0.1f, 0.2f, 0.05f};
      when(chunkEmbeddingRepository.searchSimilar(anyString(), anyInt()))
          .thenReturn(Collections.emptyList());

      chunkEmbeddingDao.searchSimilar(vector, 5);

      ArgumentCaptor<String> vectorCaptor = ArgumentCaptor.forClass(String.class);
      verify(chunkEmbeddingRepository).searchSimilar(vectorCaptor.capture(), anyInt());

      String literal = vectorCaptor.getValue();
      assertThat(literal).startsWith("[");
      assertThat(literal).endsWith("]");
      assertThat(literal).contains("0.1");
      assertThat(literal).contains("0.2");
      assertThat(literal).contains("0.05");
    }

    @Test
    @DisplayName("limit 参数透传到 Repository")
    void limitPassedThroughToRepository() {
      float[] vector = {0.5f};
      when(chunkEmbeddingRepository.searchSimilar(anyString(), anyInt()))
          .thenReturn(Collections.emptyList());

      chunkEmbeddingDao.searchSimilar(vector, 42);

      ArgumentCaptor<Integer> limitCaptor = ArgumentCaptor.forClass(Integer.class);
      verify(chunkEmbeddingRepository).searchSimilar(anyString(), limitCaptor.capture());

      assertThat(limitCaptor.getValue()).isEqualTo(42);
    }
  }
}
