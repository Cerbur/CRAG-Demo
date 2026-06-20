package ai.cerbur.crag.query.llm.contract;

/**
 * LLM 调用失败分类 —— 涵盖认证、限流、超时、协议错误、空响应、截断和服务端错误等情形.
 *
 * <p>每个分类对应一个可操作的处理意图，供调用方决定重试方式、日志级别或用户提示策略.
 */
public enum LlmFailureCategory {
  /** API 密钥无效、过期或未授权. */
  AUTHENTICATION,
  /** 请求频率超限. */
  RATE_LIMITED,
  /** 请求超时. */
  TIMEOUT,
  /** 响应结构异常（如意外 tool-use、多 generation、无法解析）. */
  PROTOCOL,
  /** 0 个生成、无文本块或 trim 后为空. */
  EMPTY_RESPONSE,
  /** 停止原因表明因 max_tokens 截断. */
  TRUNCATED_RESPONSE,
  /** 服务端 5xx 错误. */
  SERVER_ERROR,
  /** 未分类的失败（Stub 失败模式使用此分类）. */
  UNKNOWN
}
