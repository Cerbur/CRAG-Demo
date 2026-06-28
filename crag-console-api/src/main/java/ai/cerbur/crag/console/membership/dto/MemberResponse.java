package ai.cerbur.crag.console.membership.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 成员安全投影（plan_21/21.7）。
 *
 * <p>不包含密码、账号状态或登录标识。{@code nickname} 在单成员命令（add/change-role/remove）通过单用户 GetUserProfile 解析；list 受
 * Membership proto 无 nickname 字段限制暂为 {@code null}，记录为 21.7 契约缺口。
 *
 * @param userId 用户 ID（十进制字符串）
 * @param nickname 展示名；list 操作可能为 null
 * @param role 角色（OWNER/MEMBER）
 * @param status 成员状态（ACTIVE/REMOVED）
 * @param createdAt 创建时间（RFC 3339 UTC）
 * @param updatedAt 更新时间（RFC 3339 UTC）
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record MemberResponse(
    String userId,
    String nickname,
    String role,
    String status,
    java.time.Instant createdAt,
    java.time.Instant updatedAt) {}
