package ai.cerbur.crag.query.context;

import ai.cerbur.crag.query.api.QuerySource;
import java.util.Collections;
import java.util.List;

/**
 * Context 构建结果 —— 包含字符预算受控的完整 context 文本、连续编号的 source 映射列表及实际长度.
 *
 * <p>{@code contextText} 为 {@link ContextBuilder} 拼接的最终文本，包含边界标记与 parent content，不包含问题或固定提示词。
 * {@code sources} 列表与 contextText 中的边界严格一一对应，连续编号 S1..Sn，无跳号。{@code characterCount} 必须等于 {@code
 * contextText.length()}（当 sources 非空时）。
 *
 * @param contextText 完整 context 文本（UTF-16 code units），空 context 为 ""
 * @param sources 不可变的 source 映射列表，按 S1..Sn 顺序
 * @param characterCount 实际字符数，必须等于 contextText.length()
 */
public record QueryContext(String contextText, List<QuerySource> sources, int characterCount) {

  /**
   * 紧凑构造器 —— 防御性复制 sources 并验证 length 不变量.
   *
   * @throws IllegalArgumentException contextText 为 null
   * @throws IllegalArgumentException sources 为 null
   * @throws IllegalArgumentException sources 非空时 characterCount 不等于 contextText.length()
   */
  public QueryContext {
    if (contextText == null) {
      throw new IllegalArgumentException("contextText must not be null");
    }
    if (sources == null) {
      throw new IllegalArgumentException("sources must not be null");
    }
    if (sources.isEmpty() && !contextText.isEmpty()) {
      throw new IllegalArgumentException("contextText must be empty when sources is empty");
    }
    if (!sources.isEmpty() && characterCount != contextText.length()) {
      throw new IllegalArgumentException(
          "characterCount must equal contextText.length() when sources is non-empty");
    }
    sources = List.copyOf(sources);
  }

  /**
   * 返回不可修改的 source 映射列表.
   *
   * @return 不可修改的 source 列表
   */
  @Override
  public List<QuerySource> sources() {
    return Collections.unmodifiableList(sources);
  }
}
