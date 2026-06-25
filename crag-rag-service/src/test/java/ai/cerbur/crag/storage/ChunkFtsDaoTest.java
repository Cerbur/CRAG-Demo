package ai.cerbur.crag.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.cerbur.crag.storage.repository.ChunkFtsRepository;
import ai.cerbur.crag.storage.result.SparseSearchResult;
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
 * ChunkFtsDao 单元测试 —— 验证 searchFts 的空查询保护、列映射到 SparseSearchResult 和参数透传.
 *
 * <p>Repository 层通过 Mockito 隔离，聚焦 Dao 层的业务判断逻辑和 Object[] → SparseSearchResult 映射.
 *
 * @since 2026-06-15
 */
@DisplayName("ChunkFtsDao 全文检索查询")
@ExtendWith(MockitoExtension.class)
class ChunkFtsDaoTest {

  @Mock private ChunkFtsRepository chunkFtsRepository;

  @InjectMocks private ChunkFtsDao chunkFtsDao;

  @Nested
  @DisplayName("空查询/无效输入保护")
  class EmptyQueryProtection {

    @Test
    @DisplayName("query 为 null → 返回空列表，不调用 Repository")
    void nullQueryReturnsEmpty() {
      List<SparseSearchResult> results = chunkFtsDao.searchFts(null, 10);

      assertThat(results).isEmpty();
      verifyNoInteractions(chunkFtsRepository);
    }

    @Test
    @DisplayName("query 为空字符串 → 返回空列表，不调用 Repository")
    void emptyStringReturnsEmpty() {
      List<SparseSearchResult> results = chunkFtsDao.searchFts("", 10);

      assertThat(results).isEmpty();
      verifyNoInteractions(chunkFtsRepository);
    }

    @Test
    @DisplayName("query 为纯空白字符 → 返回空列表，不调用 Repository")
    void blankStringReturnsEmpty() {
      List<SparseSearchResult> results = chunkFtsDao.searchFts("   \t\n  ", 10);

      assertThat(results).isEmpty();
      verifyNoInteractions(chunkFtsRepository);
    }
  }

  @Nested
  @DisplayName("列映射正确性")
  class ColumnMapping {

    @BeforeEach
    void stubRepoReturn() {
      // 默认不返回任何结果，各 case 自行覆盖
    }

    @Test
    @DisplayName("Repository 返回空列表 → searchFts 返回空列表")
    void emptyRepositoryResultReturnsEmpty() {
      when(chunkFtsRepository.searchFts(anyString(), anyInt())).thenReturn(Collections.emptyList());

      List<SparseSearchResult> results = chunkFtsDao.searchFts("测试", 5);

      assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("单条结果 → chunkId/parentChunkId/chunkIndex/score/content 正确映射")
    void singleRowMapsCorrectly() {
      Object[] row = {100L, 200L, 3, 0.75, "全文检索匹配内容"};
      when(chunkFtsRepository.searchFts(anyString(), anyInt())).thenReturn(List.<Object[]>of(row));

      List<SparseSearchResult> results = chunkFtsDao.searchFts("关键词", 3);

      assertThat(results).hasSize(1);
      SparseSearchResult r = results.get(0);
      assertThat(r.getChunkId()).isEqualTo(100L);
      assertThat(r.getParentChunkId()).isEqualTo(200L);
      assertThat(r.getChunkIndex()).isEqualTo(3);
      assertThat(r.getSparseScore()).isEqualTo(0.75);
      assertThat(r.getContent()).isEqualTo("全文检索匹配内容");
    }

    @Test
    @DisplayName("多条结果 → 按 Repository 返回顺序映射，数量一致")
    void multipleRowsMapCorrectly() {
      Object[] row1 = {1L, 10L, 0, 0.90, "第一个匹配"};
      Object[] row2 = {2L, 20L, 1, 0.70, "第二个匹配"};
      Object[] row3 = {3L, 30L, 2, 0.50, "第三个匹配"};
      when(chunkFtsRepository.searchFts(anyString(), anyInt()))
          .thenReturn(List.<Object[]>of(row1, row2, row3));

      List<SparseSearchResult> results = chunkFtsDao.searchFts("关键词", 10);

      assertThat(results).hasSize(3);
      assertThat(results.get(0).getChunkId()).isEqualTo(1L);
      assertThat(results.get(1).getChunkId()).isEqualTo(2L);
      assertThat(results.get(2).getChunkId()).isEqualTo(3L);
    }

    @Test
    @DisplayName("score 为 Float → doubleValue() 转换正确")
    void floatScoreConvertsToDouble() {
      Object[] row = {1L, 10L, 0, 0.123f, "内容"};
      when(chunkFtsRepository.searchFts(anyString(), anyInt())).thenReturn(List.<Object[]>of(row));

      List<SparseSearchResult> results = chunkFtsDao.searchFts("q", 1);

      assertThat(results).hasSize(1);
      assertThat(results.get(0).getSparseScore())
          .isCloseTo(0.123, org.assertj.core.data.Offset.offset(0.001));
    }
  }

  @Nested
  @DisplayName("参数透传")
  class ParameterPassThrough {

    @Test
    @DisplayName("query 原始文本原样透传到 Repository")
    void queryTextPassedThroughVerbatim() {
      when(chunkFtsRepository.searchFts(anyString(), anyInt())).thenReturn(Collections.emptyList());

      chunkFtsDao.searchFts("人工智能与机器学习", 5);

      ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
      verify(chunkFtsRepository).searchFts(queryCaptor.capture(), anyInt());

      assertThat(queryCaptor.getValue()).isEqualTo("人工智能与机器学习");
    }

    @Test
    @DisplayName("limit 参数透传到 Repository")
    void limitPassedThroughToRepository() {
      when(chunkFtsRepository.searchFts(anyString(), anyInt())).thenReturn(Collections.emptyList());

      chunkFtsDao.searchFts("测试", 77);

      ArgumentCaptor<Integer> limitCaptor = ArgumentCaptor.forClass(Integer.class);
      verify(chunkFtsRepository).searchFts(anyString(), limitCaptor.capture());

      assertThat(limitCaptor.getValue()).isEqualTo(77);
    }
  }
}
