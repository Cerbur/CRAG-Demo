package ai.cerbur.crag.retrieval.dense;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.cerbur.crag.retrieval.result.DenseSearchResult;
import ai.cerbur.crag.storage.ChunkEmbeddingDao;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * DenseQueryService 单元测试 —— 验证 search 方法的输入保护、topK 生效、Dao 委托调用.
 *
 * <p>ChunkEmbeddingDao 通过 Mockito 隔离，聚焦服务层的输入校验和 DenseSearchResult 返回值透传.
 *
 * @since 2026-06-15
 */
@DisplayName("DenseQueryService 稠密查询服务")
@ExtendWith(MockitoExtension.class)
class DenseQueryServiceTest {

  @Mock private ChunkEmbeddingDao chunkEmbeddingDao;

  @InjectMocks private DenseQueryService denseQueryService;

  @Nested
  @DisplayName("无效输入保护")
  class InvalidInputProtection {

    @Test
    @DisplayName("queryEmbedding 为 null → 返回空列表，不调用 Dao")
    void nullEmbeddingReturnsEmpty() {
      List<DenseSearchResult> results = denseQueryService.search(100L, null, 10);

      assertThat(results).isEmpty();
      verifyNoInteractions(chunkEmbeddingDao);
    }

    @Test
    @DisplayName("queryEmbedding 长度为 0 → 返回空列表，不调用 Dao")
    void emptyEmbeddingReturnsEmpty() {
      List<DenseSearchResult> results = denseQueryService.search(100L, new float[0], 10);

      assertThat(results).isEmpty();
      verifyNoInteractions(chunkEmbeddingDao);
    }

    @Test
    @DisplayName("topK <= 0 → 返回空列表，不调用 Dao")
    void zeroOrNegativeTopKReturnsEmpty() {
      float[] vector = {0.1f, 0.2f};

      assertThat(denseQueryService.search(100L, vector, 0)).isEmpty();
      assertThat(denseQueryService.search(100L, vector, -1)).isEmpty();

      verifyNoInteractions(chunkEmbeddingDao);
    }
  }

  @Nested
  @DisplayName("正常调用与返回值")
  class NormalInvocation {

    @Test
    @DisplayName("正常向量 + topK > 0 → 委托 ChunkEmbeddingDao.searchSimilar 并返回 DenseSearchResult")
    void delegatesToDaoAndReturnsResult() {
      float[] vector = {0.5f, 0.6f, 0.7f};
      List<ai.cerbur.crag.storage.result.DenseSearchResult> daoResults =
          List.of(
              new ai.cerbur.crag.storage.result.DenseSearchResult(1001L, 100L, 0.95, "结果一"),
              new ai.cerbur.crag.storage.result.DenseSearchResult(1002L, 200L, 0.80, "结果二"));
      when(chunkEmbeddingDao.searchSimilar(anyLong(), any(), anyInt())).thenReturn(daoResults);

      List<DenseSearchResult> results = denseQueryService.search(100L, vector, 10);

      assertThat(results).hasSize(2);
      assertThat(results.get(0).getContent()).isEqualTo("结果一");
      assertThat(results.get(1).getContent()).isEqualTo("结果二");
    }

    @Test
    @DisplayName("topK 参数透传到 Dao")
    void topKPassedThroughToDao() {
      float[] vector = {0.1f};

      denseQueryService.search(100L, vector, 15);

      verify(chunkEmbeddingDao).searchSimilar(100L, vector, 15);
    }

    @Test
    @DisplayName("Dao 返回空列表时正确返回空列表")
    void daoReturnsEmptyThenServiceReturnsEmpty() {
      float[] vector = {0.3f};
      when(chunkEmbeddingDao.searchSimilar(anyLong(), any(), anyInt()))
          .thenReturn(Collections.emptyList());

      List<DenseSearchResult> results = denseQueryService.search(100L, vector, 5);

      assertThat(results).isEmpty();
    }
  }
}
