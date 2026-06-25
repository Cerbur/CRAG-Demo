package ai.cerbur.crag.query.context;

import java.util.Arrays;
import java.util.List;

/**
 * 测试用 SourceBoundaryFactory —— 使用固定 nonce 序列实现确定性测试.
 *
 * <p>nonce 按创建边界时的调用顺序依次消耗。如果 nonce 耗尽但仍有调用，抛出 IllegalStateException.
 */
public class TestSourceBoundaryFactory implements SourceBoundaryFactory {

  private final List<String> nonces;
  private int index;

  /**
   * 构造工厂.
   *
   * @param nonces 固定 nonce 序列，按调用顺序消耗
   */
  public TestSourceBoundaryFactory(String... nonces) {
    this.nonces = Arrays.asList(nonces);
    this.index = 0;
  }

  /**
   * 构造工厂.
   *
   * @param nonces 固定 nonce 列表，按调用顺序消耗
   */
  public TestSourceBoundaryFactory(List<String> nonces) {
    this.nonces = List.copyOf(nonces);
    this.index = 0;
  }

  @Override
  public String createBoundary(String reference) {
    if (index >= nonces.size()) {
      throw new IllegalStateException("TestSourceBoundaryFactory nonce exhausted");
    }
    String nonce = nonces.get(index++);
    return "<CRAG:" + nonce + ":" + reference + ">";
  }

  /** 重置 nonce 索引，允许重复使用同一工厂. */
  public void reset() {
    this.index = 0;
  }
}
