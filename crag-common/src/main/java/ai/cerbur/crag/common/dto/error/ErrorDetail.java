package ai.cerbur.crag.common.dto.error;

import java.util.List;

/**
 * 统一错误详情（plan_21/21.6 正式 HTTP 入口）。
 *
 * <p>由各入口的 {@code GlobalExceptionHandler} 构造，作为 {@code Response<ErrorDetail>} 的 {@code result}
 * 字段返回。 字段语义：
 *
 * <ul>
 *   <li>{@code message} 面向客户端的安全稳定说明，不泄漏凭据失败原因、堆栈或下游原始报错。
 *   <li>{@code traceId} 贯穿 HTTP/gRPC/事件的请求标识，便于定位；不包含敏感信息。
 *   <li>{@code reason} 稳定的机器可读原因标签（对应 ResponseCode 之外的业务子因），稳定不本地化。
 *   <li>{@code retryable} 指示客户端是否可安全重试。
 *   <li>{@code fieldErrors} 校验类错误的逐字段明细，可为空列表。
 * </ul>
 *
 * @param message 安全稳定说明
 * @param traceId 请求标识
 * @param reason 稳定原因标签
 * @param retryable 是否可安全重试
 * @param fieldErrors 字段级明细，可为空
 */
public record ErrorDetail(
    String message,
    String traceId,
    String reason,
    boolean retryable,
    List<FieldErrorDetail> fieldErrors) {

  /** 构造无字段明细的错误详情（fieldErrors 为空列表）. */
  public ErrorDetail(String message, String traceId, String reason, boolean retryable) {
    this(message, traceId, reason, retryable, List.of());
  }
}
