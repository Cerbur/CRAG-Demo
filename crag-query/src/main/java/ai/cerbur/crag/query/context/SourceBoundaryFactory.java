package ai.cerbur.crag.query.context;

/**
 * Source 边界标记工厂 —— 为每个 included parent 生成防碰撞的边界标记.
 *
 * <p>生产实现使用随机 nonce 并扫描全部 evidence 内容确保边界字符串不会出现在原始资料中，防止指令注入.
 */
@FunctionalInterface
public interface SourceBoundaryFactory {

  /**
   * 为给定引用编号创建开边界标记.
   *
   * <p>返回的字符串格式为 {@code <CRAG:<nonce>:<reference>>}，例如 {@code <CRAG:abc123:S1>}. 调用方可通过在 {@code
   * '<'} 后插入 {@code '/'} 推导闭边界标记: {@code </CRAG:abc123:S1>}.
   *
   * @param reference 引用编号，如 "S1", "S2"
   * @return 开边界字符串
   */
  String createBoundary(String reference);
}
