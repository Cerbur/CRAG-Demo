package ai.cerbur.crag.console.membership.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 添加成员请求（plan_21/21.7）。按 Username 添加已注册用户；不含 actor/tenant。 */
public record AddMemberRequest(
    @NotBlank(message = "username must not be blank")
        @Size(min = 3, max = 32, message = "username must be 3-32 chars")
        String username) {}
