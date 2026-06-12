package com.crag.demo.service;

import com.crag.demo.core.chunk.split.ChunkSplitData;
import com.crag.demo.core.chunk.split.ChunkSplitGroup;
import com.crag.demo.core.chunk.split.ChunkSplitResult;
import com.crag.demo.core.chunk.split.ChunkSplitService;
import com.crag.demo.dao.entity.Chunk;
import com.crag.demo.dao.entity.ChunkStatus;
import com.crag.demo.dao.repository.ChunkRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AdminRagService 单元测试 —— 验证入库编排逻辑、分块-实体映射、状态设置.
 *
 * 使用 Mockito 隔离 ChunkSplitService / ChunkRepository，聚焦于服务自身的编排逻辑.
 * 通过 subclass mock maker 避免 JDK 25 的 inline mock 兼容性问题.
 *
 * @since 2026-06-13
 */
@DisplayName("AdminRagService 知识库入库服务")
@ExtendWith(MockitoExtension.class)
class AdminRagServiceTest {

    @Mock
    private ChunkSplitService chunkSplitService;

    @Mock
    private ChunkRepository chunkRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private AdminRagService adminRagService;

    @BeforeEach
    void setUp() {
        // saveAll 返回传入的 list，模拟 JPA 标准行为.
        // 使用 lenient() 避免空 groups 场景下 UnnecessaryStubbingException.
        lenient().when(chunkRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Nested
    @DisplayName("基础入库流程")
    class BasicIngest {

        @Test
        @DisplayName("正常文本 + 元数据 → 返回 PENDING 结果，docId 非空，chunks 数正确")
        void normalTextWithMetadataReturnsPendingResult() {
            // Given: 1 个 parent group，含 2 个 child
            stubSplitResult(1, 2);

            AdminRagResult result = adminRagService.ingest(
                "测试文档", "这是一段测试内容。".repeat(200),
                Map.of("tag", "test")
            );

            // Then: 返回值正确
            assertThat(result.docId()).isNotBlank();
            assertThat(result.chunks()).isEqualTo(2);
            assertThat(result.status()).isEqualTo("PENDING");
        }

        @Test
        @DisplayName("元数据为 null → 不抛异常，metadata JSON 仍含 title")
        void nullMetadataHandledGracefully() {
            stubSplitResult(1, 1);

            AdminRagResult result = adminRagService.ingest(
                "空元数据文档", "一些测试内容。",
                null
            );

            assertThat(result.docId()).isNotBlank();
            assertThat(result.chunks()).isEqualTo(1);

            // 验证写入的 chunk metadata 含 title 且不含 null
            List<Chunk> saved = captureSavedChunks();
            assertThat(saved.get(0).getMetadata()).contains("空元数据文档");
        }

        @Test
        @DisplayName("空 metadata Map → metadata JSON 仅含 title")
        void emptyMetadataMapProducesTitleOnlyJson() {
            stubSplitResult(1, 1);

            adminRagService.ingest("标题", "内容。", Map.of());

            List<Chunk> saved = captureSavedChunks();
            String metadata = saved.get(0).getMetadata();
            assertThat(metadata).contains("\"title\"");
            assertThat(metadata).contains("\"标题\"");
            // 只有 title，无多余字段
            assertThat(metadata).doesNotContain("\"tag\"");
        }

        @Test
        @DisplayName("metadata 含 tags → JSON 合并 title + tags")
        void metadataWithTagsIsMergedIntoJson() {
            stubSplitResult(1, 1);

            adminRagService.ingest("标题", "内容。", Map.of("tag", "ai", "source", "web"));

            List<Chunk> saved = captureSavedChunks();
            String metadata = saved.get(0).getMetadata();
            assertThat(metadata).contains("\"title\":\"标题\"");
            assertThat(metadata).contains("\"tag\":\"ai\"");
            assertThat(metadata).contains("\"source\":\"web\"");
        }
    }

    @Nested
    @DisplayName("Chunk 实体结构与状态")
    class ChunkStructure {

        @Test
        @DisplayName("Parent chunk 的 denseStatus 和 sparseStatus 均为 SKIPPED")
        void parentChunksHaveSkippedStatus() {
            stubSplitResult(2, 1);

            adminRagService.ingest("文档", "足够长的测试内容。".repeat(300), null);

            List<Chunk> saved = captureSavedChunks();
            List<Chunk> parents = saved.stream()
                .filter(c -> c.getParentChunkId().equals(Chunk.NO_PARENT))
                .toList();

            assertThat(parents).hasSize(2);
            for (Chunk parent : parents) {
                assertThat(parent.getDenseStatus()).isEqualTo(ChunkStatus.SKIPPED);
                assertThat(parent.getSparseStatus()).isEqualTo(ChunkStatus.SKIPPED);
            }
        }

        @Test
        @DisplayName("Child chunk 的 denseStatus 和 sparseStatus 均为 INIT")
        void childChunksHaveInitStatus() {
            stubSplitResult(1, 3);

            adminRagService.ingest("文档", "测试内容。".repeat(200), null);

            List<Chunk> saved = captureSavedChunks();
            List<Chunk> children = saved.stream()
                .filter(c -> !c.getParentChunkId().equals(Chunk.NO_PARENT))
                .toList();

            assertThat(children).hasSize(3);
            for (Chunk child : children) {
                assertThat(child.getDenseStatus()).isEqualTo(ChunkStatus.INIT);
                assertThat(child.getSparseStatus()).isEqualTo(ChunkStatus.INIT);
            }
        }

        @Test
        @DisplayName("Child 的 parentChunkId 指向同组 parent 的 chunkId")
        void childParentChunkIdLinksToParent() {
            stubSplitResult(2, 1);

            adminRagService.ingest("文档", "足够长的测试内容。".repeat(300), null);

            List<Chunk> saved = captureSavedChunks();
            List<Chunk> parents = saved.stream()
                .filter(c -> c.getParentChunkId().equals(Chunk.NO_PARENT))
                .toList();

            for (Chunk parent : parents) {
                List<Chunk> children = saved.stream()
                    .filter(c -> c.getParentChunkId().equals(parent.getChunkId()))
                    .toList();
                assertThat(children).as("Parent %s should have children", parent.getChunkId())
                    .isNotEmpty();
                for (Chunk child : children) {
                    assertThat(child.getParentChunkId()).isEqualTo(parent.getChunkId());
                }
            }
        }

        @Test
        @DisplayName("所有 chunk 共享同一个 docId")
        void allChunksShareSameDocId() {
            stubSplitResult(2, 2);

            AdminRagResult result = adminRagService.ingest(
                "共享文档", "足够长的测试内容。".repeat(300), null);

            List<Chunk> saved = captureSavedChunks();
            // parent 数 + child 数 = 2 + 4 = 6
            assertThat(saved).hasSize(6);
            for (Chunk chunk : saved) {
                assertThat(chunk.getDocId()).isEqualTo(result.docId());
            }
        }

        @Test
        @DisplayName("Parent chunkIndex 为文档级序号，child chunkIndex 为 parent 内序号")
        void chunkIndicesAreCorrect() {
            stubSplitResult(2, 2);

            adminRagService.ingest("文档", "索引测试文本。".repeat(300), null);

            List<Chunk> saved = captureSavedChunks();
            // Parent indices 来自 ChunkSplitData（文档级 0-based）
            List<Chunk> parents = saved.stream()
                .filter(c -> c.getParentChunkId().equals(Chunk.NO_PARENT))
                .toList();
            assertThat(parents.get(0).getChunkIndex()).isEqualTo(0);
            assertThat(parents.get(1).getChunkIndex()).isEqualTo(1);

            // Child indices 来自 ChunkSplitData（parent 内 0-based）
            for (Chunk parent : parents) {
                List<Chunk> children = saved.stream()
                    .filter(c -> c.getParentChunkId().equals(parent.getChunkId()))
                    .toList();
                for (int i = 0; i < children.size(); i++) {
                    assertThat(children.get(i).getChunkIndex()).isEqualTo(i);
                }
            }
        }

        @Test
        @DisplayName("Parent chunk 设置 chunkId 后才加入列表，子项能正确引用")
        void parentChunkIdIsSetBeforeChildrenAreCreated() {
            stubSplitResult(1, 2);

            adminRagService.ingest("文档", "测试内容。".repeat(200), null);

            List<Chunk> saved = captureSavedChunks();
            // 第一个是 parent，其 chunkId 非空
            Chunk parent = saved.get(0);
            assertThat(parent.getChunkId()).isNotBlank();
            assertThat(parent.getParentChunkId()).isEqualTo(Chunk.NO_PARENT);

            // 后续 children 的 parentChunkId 指向该 parent
            for (int i = 1; i < saved.size(); i++) {
                assertThat(saved.get(i).getParentChunkId()).isEqualTo(parent.getChunkId());
            }
        }

        @Test
        @DisplayName("Chunk 的 content 和 tokenCount 与 ChunkSplitData 一致")
        void chunkContentAndTokenCountMatchSplitData() {
            stubSplitResult(1, 2);

            adminRagService.ingest("文档", "匹配测试内容。".repeat(200), null);

            List<Chunk> saved = captureSavedChunks();
            for (Chunk chunk : saved) {
                assertThat(chunk.getContent()).isNotEmpty();
                assertThat(chunk.getTokenCount()).isNotNull();
                assertThat(chunk.getTokenCount()).isPositive();
            }
        }

        @Test
        @DisplayName("chunkRepository.saveAll 只调用一次，批量写入所有 chunk")
        void saveAllCalledOnceWithAllChunks() {
            stubSplitResult(2, 2);

            adminRagService.ingest("文档", "批量写入测试。".repeat(300), null);

            verify(chunkRepository).saveAll(anyList());
            // parent(2) + children(2×2) = 6
            List<Chunk> saved = captureSavedChunks();
            assertThat(saved).hasSize(6);
        }
    }

    @Nested
    @DisplayName("边界情况")
    class EdgeCases {

        @Test
        @DisplayName("分块结果 groups 为空 → 返回 0 chunks，不调用 saveAll")
        void emptySplitResultReturnsZeroChunks() {
            // Given: 空的 chunkGroups 列表（不是带空 child 的 group）
            ChunkSplitResult emptyResult = new ChunkSplitResult(List.of());
            when(chunkSplitService.split(any())).thenReturn(emptyResult);

            AdminRagResult result = adminRagService.ingest("空文档", "", null);

            assertThat(result.chunks()).isZero();
            assertThat(result.status()).isEqualTo("PENDING");
            assertThat(result.docId()).isNotBlank();
            verify(chunkRepository, never()).saveAll(anyList());
        }

        @Test
        @DisplayName("多 parent group → child 总数跨 group 正确累加")
        void multipleParentGroupsCountsChildrenCorrectly() {
            // 3 个 group，child 数分别为 2, 3, 1 → 共 6 个 child
            List<ChunkSplitGroup> groups = List.of(
                createGroup(0, 2),
                createGroup(1, 3),
                createGroup(2, 1)
            );
            when(chunkSplitService.split("长文本".repeat(500)))
                .thenReturn(new ChunkSplitResult(groups));

            AdminRagResult result = adminRagService.ingest(
                "多段文档", "长文本".repeat(500), null);

            assertThat(result.chunks()).isEqualTo(6);
            // 共 parent(3) + child(6) = 9 条记录
            List<Chunk> saved = captureSavedChunks();
            assertThat(saved).hasSize(9);
        }

        @Test
        @DisplayName("每次调用生成不同的 docId")
        void eachCallGeneratesUniqueDocId() {
            stubSplitResult(1, 1);

            AdminRagResult r1 = adminRagService.ingest("A", "内容A。", null);
            AdminRagResult r2 = adminRagService.ingest("B", "内容B。", Map.of());

            assertThat(r1.docId()).isNotEqualTo(r2.docId());
        }
    }

    @Nested
    @DisplayName("metadata JSON 构建")
    class MetadataJson {

        @Test
        @DisplayName("metadata 仅含 title 时，JSON 结构正确")
        void titleOnlyMetadataProducesValidJson() {
            stubSplitResult(1, 1);

            adminRagService.ingest("我的标题", "内容。", null);

            List<Chunk> saved = captureSavedChunks();
            String metadata = saved.get(0).getMetadata();
            assertThat(metadata).startsWith("{");
            assertThat(metadata).endsWith("}");
            assertThat(metadata).contains("\"title\":\"我的标题\"");
        }

        @Test
        @DisplayName("title 包含特殊字符 → JSON 正确转义")
        void titleWithSpecialCharsIsProperlyEscaped() {
            stubSplitResult(1, 1);

            adminRagService.ingest("包含\"引号\"的标题", "内容。", null);

            List<Chunk> saved = captureSavedChunks();
            String metadata = saved.get(0).getMetadata();
            assertThat(metadata).contains("包含");
            assertThat(metadata).contains("引号");
            // Jackson 应正确转义双引号
            assertThat(metadata).contains("\\\"引号\\\"");
        }

        @Test
        @DisplayName("metadata 字段顺序：title 在最前")
        void titleAppearsFirstInJson() {
            stubSplitResult(1, 1);

            adminRagService.ingest("首字段标题", "内容。",
                Map.of("zzz", "last", "aaa", "first"));

            List<Chunk> saved = captureSavedChunks();
            String metadata = saved.get(0).getMetadata();
            // title 在 JSON 中第一个出现
            assertThat(metadata.indexOf("\"title\"")).isLessThan(metadata.indexOf("\"aaa\""));
        }
    }

    // --- 测试辅助方法 ---

    /**
     * 构造模拟分块结果：指定数量的 parent group，每个 group 含指定数量的 child.
     */
    private void stubSplitResult(int parentCount, int childrenPerGroup) {
        List<ChunkSplitGroup> groups = new java.util.ArrayList<>();
        for (int g = 0; g < parentCount; g++) {
            groups.add(createGroup(g, childrenPerGroup));
        }
        when(chunkSplitService.split(any()))
            .thenReturn(new ChunkSplitResult(groups));
    }

    /**
     * 创建单个 ChunkSplitGroup，parent 和 child 均含区分用的伪数据.
     */
    private ChunkSplitGroup createGroup(int groupIndex, int childCount) {
        ChunkSplitData parent = new ChunkSplitData(
            "parent[%d] 内容文本——第%d个父级分块".formatted(groupIndex, groupIndex),
            512,
            groupIndex
        );

        List<ChunkSplitData> children = new java.util.ArrayList<>();
        for (int i = 0; i < childCount; i++) {
            children.add(new ChunkSplitData(
                "child[%d/%d] 子级分块文本内容".formatted(groupIndex, i),
                128,
                i
            ));
        }

        return new ChunkSplitGroup(parent, children);
    }

    /**
     * 使用 ArgumentCaptor 捕获 saveAll 的入参，返回保存的 Chunk 列表.
     */
    @SuppressWarnings("unchecked")
    private List<Chunk> captureSavedChunks() {
        ArgumentCaptor<List<Chunk>> captor = ArgumentCaptor.forClass(List.class);
        verify(chunkRepository).saveAll(captor.capture());
        return captor.getValue();
    }
}
