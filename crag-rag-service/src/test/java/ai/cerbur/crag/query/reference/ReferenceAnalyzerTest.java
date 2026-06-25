package ai.cerbur.crag.query.reference;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** ReferenceAnalyzer 单元测试 —— 覆盖严格 {@code [Sx]} 格式引用解析的完整场景. */
@DisplayName("ReferenceAnalyzer 引用分析")
class ReferenceAnalyzerTest {

  private ReferenceAnalyzer analyzer;

  @BeforeEach
  void setUp() {
    analyzer = new ReferenceAnalyzer();
  }

  // ============================================================
  // 边界条件
  // ============================================================

  @Nested
  @DisplayName("边界条件")
  class EdgeConditions {

    @Test
    @DisplayName("null answer → IllegalArgumentException")
    void nullAnswer() {
      assertThatIllegalArgumentException()
          .isThrownBy(() -> analyzer.analyze(null, 5))
          .withMessage("answer must not be null");
    }

    @Test
    @DisplayName("负数 sourceCount → IllegalArgumentException")
    void negativeSourceCount() {
      assertThatIllegalArgumentException()
          .isThrownBy(() -> analyzer.analyze("some answer", -1))
          .withMessage("sourceCount must not be negative, got -1");
    }

    @Test
    @DisplayName("sourceCount 为 0 → 所有引用均无效")
    void zeroSourceCount() {
      ReferenceAnalysis analysis = analyzer.analyze("Some text [S1] here [S2]", 0);

      assertThat(analysis.totalOccurrences()).isEqualTo(2);
      assertThat(analysis.validOccurrences()).isZero();
      assertThat(analysis.validSourceCount()).isZero();
      assertThat(analysis.invalidReferences()).containsExactly(1, 2);
      assertThat(analysis.unreferencedSourceCount()).isZero();
    }
  }

  // ============================================================
  // 无引用
  // ============================================================

  @Nested
  @DisplayName("无引用")
  class NoReferences {

    @Test
    @DisplayName("空文本 → 全零")
    void emptyText() {
      ReferenceAnalysis analysis = analyzer.analyze("", 5);

      assertAllZero(analysis, 5);
    }

    @Test
    @DisplayName("无任何 [Sx] 模式 → 全零")
    void noReferencePattern() {
      ReferenceAnalysis analysis = analyzer.analyze("这是一个没有引用的回答。", 5);

      assertAllZero(analysis, 5);
    }

    @Test
    @DisplayName("类似但非标准格式 [S] → 不匹配")
    void noNumberInBrackets() {
      ReferenceAnalysis analysis = analyzer.analyze("[S] without number", 5);

      assertAllZero(analysis, 5);
    }
  }

  // ============================================================
  // 有效引用
  // ============================================================

  @Nested
  @DisplayName("有效引用")
  class ValidReferences {

    @Test
    @DisplayName("单个有效 [S1] → total=1 valid=1 validSourceCount=1")
    void singleValidS1() {
      ReferenceAnalysis analysis = analyzer.analyze("根据资料[S1]，答案是A。", 5);

      assertThat(analysis.totalOccurrences()).isEqualTo(1);
      assertThat(analysis.validOccurrences()).isEqualTo(1);
      assertThat(analysis.validSourceCount()).isEqualTo(1);
      assertThat(analysis.invalidReferences()).isEmpty();
      assertThat(analysis.unreferencedSourceCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("多个有效 [S1][S2][S3] → 正确计数")
    void multipleValid() {
      ReferenceAnalysis analysis = analyzer.analyze("[S1]事实A[S2]事实B[S3]事实C", 5);

      assertThat(analysis.totalOccurrences()).isEqualTo(3);
      assertThat(analysis.validOccurrences()).isEqualTo(3);
      assertThat(analysis.validSourceCount()).isEqualTo(3);
      assertThat(analysis.invalidReferences()).isEmpty();
      assertThat(analysis.unreferencedSourceCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("[S12] 两位数有效引用")
    void doubleDigitValid() {
      ReferenceAnalysis analysis = analyzer.analyze("Multiple sources [S12]", 15);

      assertThat(analysis.totalOccurrences()).isEqualTo(1);
      assertThat(analysis.validOccurrences()).isEqualTo(1);
      assertThat(analysis.validSourceCount()).isEqualTo(1);
    }
  }

  // ============================================================
  // 无效引用
  // ============================================================

  @Nested
  @DisplayName("无效引用")
  class InvalidReferences {

    @Test
    @DisplayName("[S0] → 无效引用")
    void s0IsInvalid() {
      ReferenceAnalysis analysis = analyzer.analyze("[S0] invalid", 5);

      assertThat(analysis.totalOccurrences()).isEqualTo(1);
      assertThat(analysis.validOccurrences()).isZero();
      assertThat(analysis.validSourceCount()).isZero();
      assertThat(analysis.invalidReferences()).containsExactly(0);
    }

    @Test
    @DisplayName("[S99] 超出 sourceCount=3 → 无效")
    void s99ExceedsSourceCount() {
      ReferenceAnalysis analysis = analyzer.analyze("[S99] too high", 3);

      assertThat(analysis.totalOccurrences()).isEqualTo(1);
      assertThat(analysis.validOccurrences()).isZero();
      assertThat(analysis.validSourceCount()).isZero();
      assertThat(analysis.invalidReferences()).containsExactly(99);
    }

    @Test
    @DisplayName("[S01] 前导零 → 不是有效引用")
    void leadingZeroIsNotValid() {
      // [S01] has a leading zero — not strict format
      // The regex matches \d+ which includes "01", but leading zeros are invalid
      ReferenceAnalysis analysis = analyzer.analyze("[S01] has leading zero", 5);

      // totalOccurrences counts all [S\d+] matches, including leading zeros
      assertThat(analysis.totalOccurrences()).isEqualTo(1);
      // validOccurrences should NOT count [S01] because it has leading zero
      assertThat(analysis.validOccurrences()).isZero();
      assertThat(analysis.validSourceCount()).isZero();
      // invalidReferences tracks invalid numbers by value, but "01" parsed as 1 would be valid
      // Since leading zeros are format-invalid, we skip them entirely
      assertThat(analysis.invalidReferences()).isEmpty();
    }

    @Test
    @DisplayName("[s1] 小写 s → 不匹配")
    void lowercaseSDoesNotMatch() {
      ReferenceAnalysis analysis = analyzer.analyze("[s1] lowercase", 5);

      assertAllZero(analysis, 5);
    }

    @Test
    @DisplayName("[S 1] 空格 → 不匹配")
    void spaceInBracketsDoesNotMatch() {
      ReferenceAnalysis analysis = analyzer.analyze("[S 1] with space", 5);

      assertAllZero(analysis, 5);
    }

    @Test
    @DisplayName("多个不同无效引用 → 按首次出现顺序返回")
    void multipleDistinctInvalid() {
      ReferenceAnalysis analysis = analyzer.analyze("[S99] then [S0] then [S99] again [S100]", 5);

      assertThat(analysis.totalOccurrences()).isEqualTo(4);
      assertThat(analysis.validOccurrences()).isZero();
      assertThat(analysis.validSourceCount()).isZero();
      // Distinct invalid numbers in first-occurrence order
      assertThat(analysis.invalidReferences()).containsExactly(99, 0, 100);
    }
  }

  // ============================================================
  // 重复引用
  // ============================================================

  @Nested
  @DisplayName("重复引用")
  class DuplicateReferences {

    @Test
    @DisplayName("重复 [S1][S1] → total=2 valid=2 validSourceCount=1")
    void duplicateValid() {
      ReferenceAnalysis analysis = analyzer.analyze("First [S1] and second [S1]", 5);

      assertThat(analysis.totalOccurrences()).isEqualTo(2);
      assertThat(analysis.validOccurrences()).isEqualTo(2);
      assertThat(analysis.validSourceCount()).isEqualTo(1);
      assertThat(analysis.unreferencedSourceCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("重复无效引用在 invalidReferences 中只出现一次")
    void duplicateInvalidDeduplicated() {
      ReferenceAnalysis analysis = analyzer.analyze("[S99] and [S99] again", 5);

      assertThat(analysis.totalOccurrences()).isEqualTo(2);
      assertThat(analysis.invalidReferences()).containsExactly(99);
    }
  }

  // ============================================================
  // 混合有效 / 无效
  // ============================================================

  @Nested
  @DisplayName("混合有效与无效")
  class MixedReferences {

    @Test
    @DisplayName("[S1] valid, [S99] invalid, [S2] valid")
    void mixedValidInvalid() {
      ReferenceAnalysis analysis = analyzer.analyze("[S1] valid [S99] invalid [S2] valid", 5);

      assertThat(analysis.totalOccurrences()).isEqualTo(3);
      assertThat(analysis.validOccurrences()).isEqualTo(2);
      assertThat(analysis.validSourceCount()).isEqualTo(2);
      assertThat(analysis.invalidReferences()).containsExactly(99);
      assertThat(analysis.unreferencedSourceCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("[S0] and [S1] mixed → one valid one invalid")
    void s0AndS1() {
      ReferenceAnalysis analysis = analyzer.analyze("[S0] invalid [S1] valid", 3);

      assertThat(analysis.totalOccurrences()).isEqualTo(2);
      assertThat(analysis.validOccurrences()).isEqualTo(1);
      assertThat(analysis.validSourceCount()).isEqualTo(1);
      assertThat(analysis.invalidReferences()).containsExactly(0);
    }
  }

  // ============================================================
  // 代码块内的引用（同等处理）
  // ============================================================

  @Nested
  @DisplayName("代码块内的引用")
  class CodeBlockReferences {

    @Test
    @DisplayName("代码块内引用也被解析")
    void referencesInsideCodeBlock() {
      String answer =
          "根据资料[S1]，代码如下：\n" + "```\n" + "// See [S2] for details\n" + "x = 1;\n" + "```";

      ReferenceAnalysis analysis = analyzer.analyze(answer, 3);

      assertThat(analysis.totalOccurrences()).isEqualTo(2);
      assertThat(analysis.validOccurrences()).isEqualTo(2);
      assertThat(analysis.validSourceCount()).isEqualTo(2);
    }
  }

  // ============================================================
  // 综合场景
  // ============================================================

  @Nested
  @DisplayName("综合场景")
  class Comprehensive {

    @Test
    @DisplayName("全部四个 source 都被引用")
    void allSourcesReferenced() {
      ReferenceAnalysis analysis = analyzer.analyze("[S1] [S2] [S3] [S4]", 4);

      assertThat(analysis.totalOccurrences()).isEqualTo(4);
      assertThat(analysis.validOccurrences()).isEqualTo(4);
      assertThat(analysis.validSourceCount()).isEqualTo(4);
      assertThat(analysis.unreferencedSourceCount()).isZero();
    }

    @Test
    @DisplayName("重复引用同一 source 不影响 unreferencedSourceCount")
    void duplicateDoesNotAffectUnreferencedCount() {
      ReferenceAnalysis analysis = analyzer.analyze("[S1] [S1] [S1]", 3);

      assertThat(analysis.totalOccurrences()).isEqualTo(3);
      assertThat(analysis.validOccurrences()).isEqualTo(3);
      assertThat(analysis.validSourceCount()).isEqualTo(1);
      assertThat(analysis.unreferencedSourceCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("sourceCount 大于所有引用 → unreferencedSourceCount 为正")
    void someSourcesUnreferenced() {
      ReferenceAnalysis analysis = analyzer.analyze("[S2]", 10);

      assertThat(analysis.totalOccurrences()).isEqualTo(1);
      assertThat(analysis.validOccurrences()).isEqualTo(1);
      assertThat(analysis.validSourceCount()).isEqualTo(1);
      assertThat(analysis.unreferencedSourceCount()).isEqualTo(9);
    }
  }

  // ============================================================
  // Helper
  // ============================================================

  private static void assertAllZero(ReferenceAnalysis analysis, int sourceCount) {
    assertThat(analysis.totalOccurrences()).isZero();
    assertThat(analysis.validOccurrences()).isZero();
    assertThat(analysis.validSourceCount()).isZero();
    assertThat(analysis.invalidReferences()).isEmpty();
    assertThat(analysis.unreferencedSourceCount()).isEqualTo(sourceCount);
  }
}
