package ai.cerbur.crag.query.context;

import ai.cerbur.crag.query.api.QuerySource;
import ai.cerbur.crag.retrieval.api.result.ParentEvidenceResult;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Evidence 上下文构建器 —— 在字符预算内将排序后的 parent evidence 转换为带有防碰撞边界标记的上下文.
 *
 * <p>核心职责：
 *
 * <ul>
 *   <li>按 Retrieval 顺序逐个尝试添加 parent（整块添加，不截断）
 *   <li>超出预算的整个 parent 跳过并继续尝试后续
 *   <li>重复 parentChunkId 只保留首个，后续跳过
 *   <li>被实际包含的 evidence 获得连续编号 S1..Sn，被跳过的证据不占据编号
 *   <li>通过 {@link SourceBoundaryFactory} 为每个 parent 生成防碰撞边界标记
 * </ul>
 */
@Component
public class ContextBuilder {

  /**
   * 构建查询上下文.
   *
   * @param evidence 排序后的 parent evidence 列表；不能为 null 或包含 null 元素
   * @param maxCharacters 最大字符预算（UTF-16 code units）
   * @param boundaryFactory 边界标记工厂
   * @return 包含上下文文本与 source 映射的 {@link QueryContext}
   * @throws IllegalArgumentException evidence 为 null
   * @throws IllegalArgumentException evidence 包含 null 元素
   * @throws IllegalArgumentException maxCharacters 为负数
   * @throws IllegalArgumentException boundaryFactory 为 null
   */
  public QueryContext build(
      List<ParentEvidenceResult> evidence,
      int maxCharacters,
      SourceBoundaryFactory boundaryFactory) {

    if (evidence == null) {
      throw new IllegalArgumentException("evidence must not be null");
    }
    if (maxCharacters < 0) {
      throw new IllegalArgumentException("maxCharacters must not be negative");
    }
    if (boundaryFactory == null) {
      throw new IllegalArgumentException("boundaryFactory must not be null");
    }

    for (ParentEvidenceResult e : evidence) {
      if (e == null) {
        throw new IllegalArgumentException("evidence must not contain null elements");
      }
    }

    if (evidence.isEmpty()) {
      return new QueryContext("", List.of(), 0);
    }

    Set<String> seenIds = new HashSet<>();
    List<QuerySource> sources = new ArrayList<>();
    StringBuilder contextBuilder = new StringBuilder();
    int includedCount = 0;

    for (ParentEvidenceResult item : evidence) {
      // Duplicate parentChunkId -> skip (keep first)
      if (!seenIds.add(item.parentChunkId())) {
        continue;
      }

      // Tentative source number (will be this if budget permits)
      int tentativeNumber = includedCount + 1;
      String reference = "S" + tentativeNumber;
      String openingBoundary = boundaryFactory.createBoundary(reference);
      String closingBoundary = "</" + openingBoundary.substring(1);

      // Build the complete block for this source
      String block = openingBoundary + "\n" + item.content() + "\n" + closingBoundary;

      // Check budget: current length + separator (if any) + block
      int separatorLen = contextBuilder.length() > 0 ? 2 : 0;
      if (contextBuilder.length() + separatorLen + block.length() > maxCharacters) {
        continue; // skip this entire parent, try next
      }

      // Budget allows — append
      if (contextBuilder.length() > 0) {
        contextBuilder.append("\n\n");
      }
      contextBuilder.append(block);
      includedCount++;

      sources.add(new QuerySource(reference, item.parentChunkId(), item.matchedChildIds()));
    }

    return new QueryContext(
        contextBuilder.toString(), List.copyOf(sources), contextBuilder.length());
  }
}
