package ai.cerbur.crag.query.reference;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 引用分析器 —— 解析 LLM 回答文本中 {@code [Sx]} 格式的引用.
 *
 * <p>严格格式定义：
 *
 * <ul>
 *   <li>{@code S} 必须大写
 *   <li>{@code [S]} 与数字之间无空格
 *   <li>数字无前导零（如 {@code [S01]} 无效）
 *   <li>代码块内的引用同样被解析（不做特殊处理）
 * </ul>
 *
 * <p>由 {@code UserQueryService} 在 LLM 回答后调用.
 */
@Component
public class ReferenceAnalyzer {

  private static final Pattern REFERENCE_PATTERN = Pattern.compile("\\[S(\\d+)\\]");

  /**
   * 分析 LLM 回答中的引用.
   *
   * @param answer LLM 生成的回答文本
   * @param sourceCount Context 中提供的有效 source 数量
   * @return {@link ReferenceAnalysis} 包含完整引用统计
   * @throws IllegalArgumentException answer 为 null
   * @throws IllegalArgumentException sourceCount 为负数
   */
  public ReferenceAnalysis analyze(String answer, int sourceCount) {
    if (answer == null) {
      throw new IllegalArgumentException("answer must not be null");
    }
    if (sourceCount < 0) {
      throw new IllegalArgumentException("sourceCount must not be negative, got " + sourceCount);
    }

    int totalOccurrences = 0;
    int validOccurrences = 0;
    Set<Integer> validSources = new LinkedHashSet<>();
    List<Integer> invalidReferences = new ArrayList<>();
    Set<Integer> seenInvalid = new LinkedHashSet<>();

    Matcher matcher = REFERENCE_PATTERN.matcher(answer);
    while (matcher.find()) {
      totalOccurrences++;
      String numberStr = matcher.group(1);

      // Check for leading zeros — if the matched digits have a leading zero, skip
      // as the strict format disallows [S01]
      if (numberStr.length() > 1 && numberStr.startsWith("0")) {
        // This is an invalid reference due to leading zeros
        // We don't add it to invalidReferences since we only track by number,
        // and "01" as a number is 1 which would be valid — but the format [S01] is not.
        // We count it as an occurrence but not as a valid or tracked invalid reference.
        continue;
      }

      int refNumber = Integer.parseInt(numberStr);

      if (refNumber >= 1 && refNumber <= sourceCount) {
        validOccurrences++;
        validSources.add(refNumber);
      } else {
        if (seenInvalid.add(refNumber)) {
          invalidReferences.add(refNumber);
        }
      }
    }

    int validSourceCount = validSources.size();
    int unreferencedSourceCount = sourceCount - validSourceCount;

    return new ReferenceAnalysis(
        totalOccurrences,
        validOccurrences,
        validSourceCount,
        invalidReferences,
        unreferencedSourceCount);
  }
}
