package ai.cerbur.crag.query.reference;

import java.util.Collections;
import java.util.List;

/**
 * 引用分析结果 —— 统计 LLM 回答中 {@code [Sx]} 格式引用的出现情况.
 *
 * <p>所有计数字段均为只读统计值，{@code invalidReferences} 为不可变列表.
 *
 * @param totalOccurrences ALL 严格格式 {@code [S\d+]} 的总出现次数（包括无效编号和重复）
 * @param validOccurrences 编号在 1..sourceCount 范围内的有效引用出现次数（包括重复）
 * @param validSourceCount 被引用的不同有效 source 数量
 * @param invalidReferences 无效引用的编号列表（去重，按首次出现顺序）
 * @param unreferencedSourceCount 未被引用的 source 数量（sourceCount - validSourceCount）
 */
public record ReferenceAnalysis(
    int totalOccurrences,
    int validOccurrences,
    int validSourceCount,
    List<Integer> invalidReferences,
    int unreferencedSourceCount) {

  /** 紧凑构造器 —— 防御性复制并校验非负性. */
  public ReferenceAnalysis {
    if (invalidReferences == null) {
      throw new IllegalArgumentException("invalidReferences must not be null");
    }
    invalidReferences = List.copyOf(invalidReferences);
  }

  /**
   * 返回不可修改的无效引用列表.
   *
   * @return 不可修改的整数列表
   */
  @Override
  public List<Integer> invalidReferences() {
    return Collections.unmodifiableList(invalidReferences);
  }
}
