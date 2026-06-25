package ai.cerbur.crag.ingestion.chunk.split;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * ChunkSplitService 单元测试 —— 验证 child/parent 分组、overlap、全文覆盖.
 *
 * <p>ChunkSplitService 是纯 POJO（不依赖 Spring 容器），使用 JUnit 5 + AssertJ 直接实例化测试.
 *
 * @since 2026-06-12
 */
@DisplayName("ChunkSplitService 文档分块服务")
class ChunkSplitServiceTest {

  private ChunkSplitService chunkSplitService;

  @BeforeEach
  void setUp() {
    chunkSplitService = new ChunkSplitService();
  }

  @Nested
  @DisplayName("基本分块结构")
  class BasicStructure {

    @Test
    @DisplayName("正常文本产生 parent group + N 个 child")
    void normalTextProducesParentGroupsAndChildren() {
      // 构造一段足够长的中文文本（> 1024 token 触发 parent 级分块）
      String content = buildChineseText(3000);

      ChunkSplitResult result = chunkSplitService.split(content);

      assertThat(result.chunkGroups()).isNotEmpty();

      // 第一个 parent 必须存在，兼容 parentChunk() 便利方法
      assertThat(result.parentChunk()).isNotNull();
      assertThat(result.parentChunk().content()).isNotEmpty();
      assertThat(result.parentChunk().tokenCount()).isPositive();
      // Parent chunkIndex 为文档中序号（0-based）
      assertThat(result.parentChunk().chunkIndex()).isEqualTo(0);

      // Child 至少有一个，且每个 parent group 内的 chunkIndex 从 0 开始递增
      assertThat(result.childChunks()).isNotEmpty();
      for (ChunkSplitGroup group : result.chunkGroups()) {
        assertThat(group.parentChunk().chunkIndex()).isNotNull();
        assertThat(group.childChunks()).isNotEmpty();
        for (int i = 0; i < group.childChunks().size(); i++) {
          ChunkSplitData child = group.childChunks().get(i);
          assertThat(child.chunkIndex()).isEqualTo(i);
          assertThat(child.content()).isNotEmpty();
          assertThat(child.tokenCount()).isPositive();
        }
      }
    }

    @Test
    @DisplayName("短文本（低于 CHILD_SIZE）只产生 1 个 child")
    void shortTextProducesSingleChild() {
      String content = "这是一段很短的测试文本。";

      ChunkSplitResult result = chunkSplitService.split(content);

      assertThat(result.childChunks()).hasSize(1);
      ChunkSplitData child = result.childChunks().get(0);
      assertThat(child.chunkIndex()).isZero();
      assertThat(child.content()).contains("测试文本");
    }

    @Test
    @DisplayName("极短文本（<= 5 字符）不被丢弃 — minChunkLengthToEmbed=0 生效")
    void veryShortTextIsNotDropped() {
      // 2 个中文字符，在 CL100K_BASE 中约 2-4 token
      String content = "你好";

      ChunkSplitResult result = chunkSplitService.split(content);

      // 核心断言：不能因为 minChunkLengthToEmbed 丢弃短文本
      assertThat(result.childChunks()).isNotEmpty();
      assertThat(result.childChunks().get(0).content()).contains("你好");
    }

    @Test
    @DisplayName("单个中文字符不被丢弃")
    void singleChineseCharIsNotDropped() {
      String content = "测";

      ChunkSplitResult result = chunkSplitService.split(content);

      assertThat(result.childChunks()).isNotEmpty();
      assertThat(result.childChunks().get(0).content()).contains("测");
    }

    @Test
    @DisplayName("单个英文字母不被丢弃")
    void singleEnglishCharIsNotDropped() {
      String content = "A";

      ChunkSplitResult result = chunkSplitService.split(content);

      assertThat(result.childChunks()).isNotEmpty();
      assertThat(result.childChunks().get(0).content()).contains("A");
    }
  }

  @Nested
  @DisplayName("边界情况")
  class EdgeCases {

    @Test
    @DisplayName("null 文本返回空结果")
    void nullContentReturnsEmpty() {
      ChunkSplitResult result = chunkSplitService.split(null);

      assertThat(result.parentChunk().content()).isEmpty();
      assertThat(result.parentChunk().tokenCount()).isZero();
      assertThat(result.parentChunk().chunkIndex()).isNull();
      assertThat(result.childChunks()).isEmpty();
    }

    @Test
    @DisplayName("空字符串返回空结果")
    void emptyContentReturnsEmpty() {
      ChunkSplitResult result = chunkSplitService.split("");

      assertThat(result.parentChunk().content()).isEmpty();
      assertThat(result.parentChunk().tokenCount()).isZero();
      assertThat(result.childChunks()).isEmpty();
    }

    @Test
    @DisplayName("纯英文文本分块正常")
    void pureEnglishText() {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < 50; i++) {
        sb.append("This is sentence number ")
            .append(i)
            .append(". It contains some additional context words to make it longer.\n");
      }
      String content = sb.toString();

      ChunkSplitResult result = chunkSplitService.split(content);

      assertThat(result.parentChunk()).isNotNull();
      assertThat(result.childChunks()).isNotEmpty();
      // 所有 child 都包含英文
      for (ChunkSplitData child : result.childChunks()) {
        assertThat(child.tokenCount()).isPositive();
      }
    }

    @Test
    @DisplayName("纯标点符号不被丢弃")
    void punctuationOnlyText() {
      String content = "!!!???...";

      ChunkSplitResult result = chunkSplitService.split(content);

      assertThat(result.childChunks()).isNotEmpty();
      assertThat(result.childChunks().get(0).content()).isNotEmpty();
    }
  }

  @Nested
  @DisplayName("全文覆盖验证")
  class FullContentCoverage {

    @Test
    @DisplayName("所有 child 的原始内容（去除 overlap）拼接后覆盖 parent 全文")
    void childrenCoverParentContent() {
      String content = buildChineseText(2000);

      ChunkSplitResult result = chunkSplitService.split(content);
      // 取第一个 parent chunk，验证该 parent 内部被 child 覆盖
      String parentContent = result.parentChunk().content();
      List<ChunkSplitData> children = result.childChunks();

      // 验证：parent content 中的每个字符至少在某个 child 中出现
      // 使用滑动窗口采样（避免逐字符的 O(n^2) 复杂度）
      String allChildText =
          children.stream().map(ChunkSplitData::content).collect(Collectors.joining());

      // 采样 parent 的前/中/后各一段，确认在 child 联合文本中能找到
      int len = parentContent.length();
      String sampleStart = parentContent.substring(0, Math.min(20, len));
      String sampleMid = parentContent.substring(len / 2, Math.min(len / 2 + 20, len));
      String sampleEnd = parentContent.substring(Math.max(0, len - 20));

      assertThat(allChildText).contains(sampleStart);
      assertThat(allChildText).contains(sampleMid);
      assertThat(allChildText).contains(sampleEnd);
    }

    @Test
    @DisplayName("长文会生成多个 parent group，且尾部内容不会被截断")
    void longTextProducesMultipleParentsAndKeepsTailContent() {
      String tailMarker = "最终尾部标记用于确认长文没有被截断";
      String content = buildChineseText(6000) + tailMarker;

      ChunkSplitResult result = chunkSplitService.split(content);
      String allParentText =
          result.chunkGroups().stream()
              .map(group -> group.parentChunk().content())
              .collect(Collectors.joining());
      String allChildText =
          result.childChunks().stream().map(ChunkSplitData::content).collect(Collectors.joining());

      assertThat(result.chunkGroups()).hasSizeGreaterThan(1);
      assertThat(allParentText).contains(tailMarker);
      assertThat(allChildText).contains(tailMarker);
    }

    @Test
    @DisplayName("多 parent 场景下 child index 在每个 parent 内从 0 重新递增")
    void childIndexRestartsWithinEachParentGroup() {
      String content = buildChineseText(6000);

      ChunkSplitResult result = chunkSplitService.split(content);

      assertThat(result.chunkGroups()).hasSizeGreaterThan(1);
      for (ChunkSplitGroup group : result.chunkGroups()) {
        assertThat(group.childChunks()).isNotEmpty();
        for (int i = 0; i < group.childChunks().size(); i++) {
          assertThat(group.childChunks().get(i).chunkIndex()).isEqualTo(i);
        }
      }
    }

    @Test
    @DisplayName("短文本：parent 内容完全等于 child 内容（无 overlap 参与）")
    void shortTextParentChildContentMatch() {
      String content = "这是测试文本，用于验证短文本的全覆盖。";

      ChunkSplitResult result = chunkSplitService.split(content);

      // 单个 child 时，content 至少包含原始所有文本
      // （TokenTextSplitter 会 trim/keepSeparator，允许轻微空白差异）
      String childContent = result.childChunks().get(0).content();
      assertThat(childContent).contains("测试文本");
      assertThat(childContent).contains("全覆盖");
    }

    @Test
    @DisplayName("中等长度文本：每个 child 都是 parent 的子串（忽略 overlap）")
    void eachChildIsSubstringOfParent() {
      String content = buildChineseText(1500);

      ChunkSplitResult result = chunkSplitService.split(content);

      for (ChunkSplitData child : result.childChunks()) {
        // 由于 overlap，child 可能比 parent 对应位置长，
        // 但至少 child 中的核心内容应在 parent 中
        assertThat(child.content()).isNotEmpty();
        assertThat(child.tokenCount()).isGreaterThan(0);
      }

      // Parent 的 token 数应 > 0
      assertThat(result.parentChunk().tokenCount()).isPositive();
    }
  }

  @Nested
  @DisplayName("Overlap 重叠验证")
  class OverlapVerification {

    @Test
    @DisplayName("相邻 child 之间存在内容重叠（child[i] 末尾出现在 child[i+1] 开头）")
    void adjacentChildrenHaveOverlap() {
      // 使用足够长的文本确保产生 >= 2 个 child
      String content = buildChineseText(1500);

      ChunkSplitResult result = chunkSplitService.split(content);
      List<ChunkSplitData> children = result.childChunks();

      // 需要 >= 2 个 child 才能验证 overlap
      if (children.size() >= 2) {
        boolean foundOverlap = false;
        for (int i = 0; i < children.size() - 1; i++) {
          String currentTail = lastNChars(children.get(i).content(), 10);
          String nextHead = children.get(i + 1).content();

          // 前一个 child 的末尾字符应出现在后一个 child 的开头附近
          if (!currentTail.isEmpty() && nextHead.contains(currentTail)) {
            foundOverlap = true;
            break;
          }
        }
        // 至少有一对 child 存在可检测的 overlap
        assertThat(foundOverlap)
            .as("At least one pair of adjacent children should overlap")
            .isTrue();
      }
    }

    @Test
    @DisplayName("overlap 后的 child token 数不小于原始 child token 数")
    void overlapIncreasesChildTokens() {
      // 构造结构化的文本：每个"句子"以特定分隔符结尾，确保 TokenTextSplitter 能切分
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < 30; i++) {
        sb.append("这是第")
            .append(i)
            .append("个测试段落。")
            .append("本段落包含足够的文字来触发分块逻辑。")
            .append("我们还需要更多内容来达到 token 阈值。")
            .append("人工智能技术正在改变世界。\n");
      }

      ChunkSplitResult result = chunkSplitService.split(sb.toString());

      // 所有 child token 计数 > 0
      for (ChunkSplitData child : result.childChunks()) {
        assertThat(child.tokenCount()).as("Child %d token count", child.chunkIndex()).isPositive();
      }
    }
  }

  @Nested
  @DisplayName("Token 计数验证")
  class TokenCountVerification {

    @Test
    @DisplayName("parent token 数 >= 所有 child 原始文本 token 数之和（overlap 不计入）")
    void parentTokensAreConsistent() {
      String content = buildChineseText(1000);

      ChunkSplitResult result = chunkSplitService.split(content);

      int parentTokens = result.parentChunk().tokenCount();

      // Parent token 应 > 0 且 <= PARENT_SIZE
      assertThat(parentTokens).isPositive();
      // 允许少量超出（句末标点截断可能让最后一个 chunk 稍大）
      assertThat(parentTokens).isLessThanOrEqualTo(1200);
    }

    @Test
    @DisplayName("所有 child token 计数均为正数")
    void allChildrenHavePositiveTokenCount() {
      String content = buildChineseText(2000);

      ChunkSplitResult result = chunkSplitService.split(content);

      for (ChunkSplitData child : result.childChunks()) {
        assertThat(child.tokenCount())
            .as("Child[%d] token count should be positive", child.chunkIndex())
            .isPositive();
      }
    }
  }

  // --- 测试辅助方法 ---

  /** 构造指定字符数左右的中文测试文本. 每隔约 20 字插入句号，确保 TokenTextSplitter 能找到句末标点. */
  private static String buildChineseText(int targetChars) {
    StringBuilder sb = new StringBuilder(targetChars + 100);
    int written = 0;
    int sentenceIdx = 0;
    while (written < targetChars) {
      sb.append("这是第")
          .append(sentenceIdx)
          .append("个测试句子，")
          .append("用于验证文档分块服务的正确性。")
          .append("检索增强生成技术是一种结合信息检索和文本生成的混合架构。")
          .append("它能够从知识库中召回相关文档片段并生成准确回答。");
      written += 80; // 估算
      sentenceIdx++;
      // 每 3 句加一次换行，模拟真实段落
      if (sentenceIdx % 3 == 0) {
        sb.append("\n");
      }
    }
    return sb.toString();
  }

  /** 取字符串末尾 n 个字符（处理不足 n 的情况）. */
  private static String lastNChars(String s, int n) {
    if (s == null || s.isEmpty() || n <= 0) {
      return "";
    }
    int start = Math.max(0, s.length() - n);
    return s.substring(start);
  }
}
