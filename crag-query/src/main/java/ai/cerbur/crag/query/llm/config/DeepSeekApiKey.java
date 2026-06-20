package ai.cerbur.crag.query.llm.config;

import java.util.Objects;

/**
 * DeepSeek API 密钥 —— 不可变值对象，{@link #toString()} 掩码输出确保日志安全.
 *
 * <p>错误消息和日志中不得包含实际密钥值.
 */
public final class DeepSeekApiKey {

  private final String value;

  /**
   * @param value 原始 API 密钥，允许为空字符串（上游校验）
   */
  public DeepSeekApiKey(String value) {
    this.value = value;
  }

  /** 返回原始密钥值. */
  public String value() {
    return value;
  }

  /**
   * 返回掩码后的字符串表示，不会泄露实际密钥.
   *
   * @return {@code "DeepSeekApiKey[value=****]"}
   */
  @Override
  public String toString() {
    return "DeepSeekApiKey[value=****]";
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    DeepSeekApiKey that = (DeepSeekApiKey) o;
    return Objects.equals(value, that.value);
  }

  @Override
  public int hashCode() {
    return Objects.hash(value);
  }
}
