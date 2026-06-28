package ai.cerbur.crag.common.dto.error;

/**
 * 统一字段校验错误详情（plan_21/21.6 正式 HTTP 入口）。
 *
 * <p>{@code field} 标识出错的请求字段；{@code message} 是面向客户端的安全说明，绝不回显被拒绝的敏感原值（密码、Token、API Key）。 {@code
 * rejectedValue} 仅用于明确非敏感的诊断回显（例如分页参数），敏感字段由异常映射保持 {@code null}。
 *
 * @param field 出错字段名
 * @param message 安全错误说明
 * @param rejectedValue 非敏感诊断回显；敏感字段为 {@code null}
 */
public record FieldErrorDetail(String field, String message, Object rejectedValue) {}
