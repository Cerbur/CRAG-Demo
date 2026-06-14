package ai.cerbur.crag.retrieval.sparse;

import ai.cerbur.crag.storage.ChunkFtsDao;
import ai.cerbur.crag.storage.ChunkSearchResult;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * SparseQueryService 单元测试 —— 验证 search 方法的输入保护、topK 生效、Dao 委托调用.
 *
 * ChunkFtsDao 通过 Mockito 隔离，聚焦服务层的输入校验和返回值透传.
 *
 * @since 2026-06-15
 */
@DisplayName("SparseQueryService 稀疏查询服务")
@ExtendWith(MockitoExtension.class)
class SparseQueryServiceTest {

    @Mock
    private ChunkFtsDao chunkFtsDao;

    @InjectMocks
    private SparseQueryService sparseQueryService;

    @Nested
    @DisplayName("无效输入保护")
    class InvalidInputProtection {

        @Test
        @DisplayName("query 为 null → 返回空列表，不调用 Dao")
        void nullQueryReturnsEmpty() {
            List<ChunkSearchResult> results = sparseQueryService.search(null, 10);

            assertThat(results).isEmpty();
            verifyNoInteractions(chunkFtsDao);
        }

        @Test
        @DisplayName("query 为空字符串 → 返回空列表，不调用 Dao")
        void emptyStringReturnsEmpty() {
            List<ChunkSearchResult> results = sparseQueryService.search("", 10);

            assertThat(results).isEmpty();
            verifyNoInteractions(chunkFtsDao);
        }

        @Test
        @DisplayName("query 为纯空白字符 → 返回空列表，不调用 Dao")
        void blankStringReturnsEmpty() {
            List<ChunkSearchResult> results = sparseQueryService.search("   \t\n  ", 10);

            assertThat(results).isEmpty();
            verifyNoInteractions(chunkFtsDao);
        }

        @Test
        @DisplayName("topK <= 0 → 返回空列表，不调用 Dao")
        void zeroOrNegativeTopKReturnsEmpty() {
            assertThat(sparseQueryService.search("测试", 0)).isEmpty();
            assertThat(sparseQueryService.search("测试", -1)).isEmpty();

            verifyNoInteractions(chunkFtsDao);
        }
    }

    @Nested
    @DisplayName("正常调用与返回值")
    class NormalInvocation {

        @Test
        @DisplayName("正常查询 + topK > 0 → 委托 ChunkFtsDao.searchFts 并返回结果")
        void delegatesToDaoAndReturnsResult() {
            String query = "什么是人工智能";
            List<ChunkSearchResult> daoResults = List.of(
                new ChunkSearchResult("c1", "p1", 0.90, "人工智能是..."),
                new ChunkSearchResult("c2", "p2", 0.70, "机器学习相关..."),
                new ChunkSearchResult("c3", "p3", 0.50, "深度学习介绍...")
            );
            when(chunkFtsDao.searchFts(any(), anyInt())).thenReturn(daoResults);

            List<ChunkSearchResult> results = sparseQueryService.search(query, 5);

            assertThat(results).hasSize(3);
            assertThat(results.get(0).getContent()).isEqualTo("人工智能是...");
            assertThat(results.get(1).getScore()).isEqualTo(0.70);
            assertThat(results.get(2).getChunkId()).isEqualTo("c3");
        }

        @Test
        @DisplayName("query 和 topK 参数透传到 Dao")
        void queryAndTopKPassedThroughToDao() {
            String query = "向量检索";

            sparseQueryService.search(query, 20);

            verify(chunkFtsDao).searchFts(query, 20);
        }

        @Test
        @DisplayName("Dao 返回空列表时正确返回空列表")
        void daoReturnsEmptyThenServiceReturnsEmpty() {
            when(chunkFtsDao.searchFts(any(), anyInt())).thenReturn(Collections.emptyList());

            List<ChunkSearchResult> results = sparseQueryService.search("搜索词", 5);

            assertThat(results).isEmpty();
        }
    }
}
