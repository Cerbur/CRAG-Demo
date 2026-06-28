package ai.cerbur.crag.common.dto.result;

import org.springframework.http.HttpStatus;

/**
 * 统一响应码枚举 —— 所有控制器通过 Response 包装类返回，code 字段值均来自此枚举.
 *
 * <p>每个枚举值同时持有稳定业务码、默认安全消息和对应 HTTP 状态。 HTTP 状态表达协议层结果；业务码独立稳定，不复用 HTTP 数值语义。
 *
 * @since 2026-06-13
 */
public enum ResponseCode {

  /** 通用成功. */
  SUCCESS(0, "Success", HttpStatus.OK),

  /** Bean Validation 校验失败. */
  VALIDATION_ERROR(40001, "Validation failed", HttpStatus.BAD_REQUEST),

  /** 程序化非法参数. */
  INVALID_ARGUMENT(40002, "Invalid argument", HttpStatus.BAD_REQUEST),

  /** 请求资源不存在. */
  NOT_FOUND(40401, "Resource not found", HttpStatus.NOT_FOUND),

  /** 未认证：缺少或无效凭证（plan_21/21.6 正式 HTTP 入口新增）. */
  UNAUTHENTICATED(40101, "Unauthenticated", HttpStatus.UNAUTHORIZED),

  /** 凭据无效：登录、Refresh 或 API Key 校验失败（plan_21/21.6 正式 HTTP 入口新增）. */
  INVALID_CREDENTIALS(40102, "Invalid credentials", HttpStatus.UNAUTHORIZED),

  /** 已认证但无权执行当前操作（plan_21/21.6 正式 HTTP 入口新增）. */
  FORBIDDEN(40301, "Forbidden", HttpStatus.FORBIDDEN),

  /** 资源冲突：版本冲突、状态冲突或最后 OWNER 保护（plan_21/21.6 正式 HTTP 入口新增）. */
  CONFLICT(40901, "Conflict", HttpStatus.CONFLICT),

  /** 摄取不可重试：失败分类不可重试或达到次数上限（plan_21/21.6 正式 HTTP 入口新增）. */
  INGESTION_RETRY_NOT_ALLOWED(40902, "Ingestion retry not allowed", HttpStatus.CONFLICT),

  /** 上传超过大小上限（plan_21/21.6 正式 HTTP 入口新增）. */
  UPLOAD_TOO_LARGE(41301, "Upload too large", HttpStatus.PAYLOAD_TOO_LARGE),

  /** 不支持的媒体类型（plan_21/21.6 正式 HTTP 入口新增）. */
  UNSUPPORTED_MEDIA_TYPE(41501, "Unsupported media type", HttpStatus.UNSUPPORTED_MEDIA_TYPE),

  /** 下游依赖不可用（plan_21/21.6 正式 HTTP 入口新增）. */
  DOWNSTREAM_UNAVAILABLE(50301, "Downstream unavailable", HttpStatus.SERVICE_UNAVAILABLE),

  /** 下游依赖超时（plan_21/21.6 正式 HTTP 入口新增）. */
  DOWNSTREAM_TIMEOUT(50401, "Downstream timeout", HttpStatus.GATEWAY_TIMEOUT),

  /** LLM 供应商不可用. */
  LLM_UNAVAILABLE(50201, "LLM unavailable", HttpStatus.valueOf(502)),

  /** 未预期的内部异常. */
  INTERNAL_ERROR(50001, "Internal server error", HttpStatus.INTERNAL_SERVER_ERROR);

  private final int code;
  private final String defaultMessage;
  private final HttpStatus httpStatus;

  ResponseCode(int code, String defaultMessage, HttpStatus httpStatus) {
    this.code = code;
    this.defaultMessage = defaultMessage;
    this.httpStatus = httpStatus;
  }

  /**
   * 序列化到 Response.code 字段的整型业务码.
   *
   * @return 稳定业务码
   */
  public int getCode() {
    return code;
  }

  /**
   * 默认安全消息 —— 当前不序列化到 JSON，用于日志与未来扩展.
   *
   * @return 默认安全消息
   */
  public String getDefaultMessage() {
    return defaultMessage;
  }

  /**
   * 对应的 HTTP 状态码.
   *
   * @return Spring HttpStatus
   */
  public HttpStatus getHttpStatus() {
    return httpStatus;
  }
}
