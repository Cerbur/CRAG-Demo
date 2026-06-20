package ai.cerbur.crag.query.api;

import java.util.Collections;
import java.util.List;

/**
 * 用户查询结果 —— 包含 LLM 生成的回答与对应的 source 映射列表.
 *
 * <p>{@code answer} 为 LLM 生成的回答文本，{@code sources} 为 Context 中实际包含的 source 列表（不可变）. sources
 * 允许为空（当证据不足时），非空时必须按 S1..Sn 连续编号.
 *
 * @param answer LLM 生成的回答，非 null
 * @param sources source 映射列表，非 null（可能为空）
 */
public record UserQueryResult(String answer, List<QuerySource> sources) {

  /**
   * 紧凑构造器 —— 防御性复制并拒绝 null 字段.
   *
   * @throws IllegalArgumentException answer 为 null
   * @throws IllegalArgumentException sources 为 null
   */
  public UserQueryResult {
    if (answer == null) {
      throw new IllegalArgumentException("answer must not be null");
    }
    if (sources == null) {
      throw new IllegalArgumentException("sources must not be null");
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
