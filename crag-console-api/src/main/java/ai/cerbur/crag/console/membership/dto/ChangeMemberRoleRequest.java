package ai.cerbur.crag.console.membership.dto;

import jakarta.validation.constraints.NotBlank;

/** 调整成员角色请求（plan_21/21.7）。role 取值 OWNER 或 MEMBER。 */
public record ChangeMemberRoleRequest(@NotBlank(message = "role must not be blank") String role) {}
